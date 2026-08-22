//! Small, closed controller around the pinned in-process app-server facade.
//!
//! This module is deliberately the only place that imports Codex protocol
//! types.  JNI callers can select one of six operations, but cannot provide a
//! wire method, a request id, credentials, shell command, plugin, or MCP
//! configuration.  All calls run on one bounded Tokio runtime and every
//! response is reduced to the DTOs in `boundary.rs`.

use serde_json::Value;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use codex_app_server_client::legacy_core::config::{Config, ConfigBuilder};
use codex_app_server_client::{
    EnvironmentManager, InProcessAppServerClient, InProcessClientStartArgs, InProcessServerEvent,
    TypedRequestError,
};
use codex_app_server_protocol::{
    CancelLoginAccountParams, ClientRequest, LoginAccountParams, LoginAccountResponse, RequestId,
    ServerNotification,
};
use codex_arg0::Arg0DispatchPaths;
use codex_config::{CloudConfigBundleLoader, LoaderOverrides};
use codex_features::Feature;
use codex_feedback::CodexFeedback;
use codex_http_client::{HttpClientFactory, OutboundProxyPolicy};
use codex_protocol::protocol::SessionSource;
use tokio::runtime::{Builder, Runtime};
use toml::map::Map as TomlMap;

use crate::boundary::{ErrorCode, LoginResult, RateLimitsResult, StartResult};

const LOGIN_TIMEOUT: Duration = Duration::from_secs(15 * 60);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(20);
const CHANNEL_CAPACITY: usize = 32;

// The local source patch is applied to the pinned app-server package through
// native/Cargo.toml. The gate verifies this hash and the `Skip` expression
// before allowing a release build.
const PINNED_PLUGIN_STARTUP_PROVEN_SAFE: bool = true;

#[derive(Debug)]
pub enum RuntimeError {
    Code(ErrorCode),
    Message,
}

impl RuntimeError {
    pub const fn code(&self) -> ErrorCode {
        match self {
            Self::Code(code) => *code,
            Self::Message => ErrorCode::RuntimeUnavailable,
        }
    }
}

struct ActiveLogin {
    id: String,
    started: Instant,
    // The app-server emits AccountLoginCompleted before its post-login reload
    // work and the matching AccountUpdated notification. Do not expose the
    // session as authenticated until that second notification is consumed.
    completion_seen: bool,
}

struct Inner {
    runtime: Runtime,
    client: Option<InProcessAppServerClient>,
    next_request_id: i64,
    active_login: Option<ActiveLogin>,
    authenticated: bool,
    // A refresh notification can be delivered after a rate-limit request has
    // already failed at the backend. Retain only this non-secret bit until a
    // subsequent successful read can report it to Kotlin.
    pending_auth_refresh_observed: bool,
    files_dir: Option<PathBuf>,
}

/// Synchronous JNI-facing owner. JNI calls serialize at this mutex boundary;
/// the embedded client itself still uses bounded request/event queues.
pub struct RuntimeController {
    inner: std::sync::Mutex<Inner>,
}

impl RuntimeController {
    pub fn new() -> Result<Self, RuntimeError> {
        let runtime = Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|_| RuntimeError::Message)?;
        Ok(Self {
            inner: std::sync::Mutex::new(Inner {
                runtime,
                client: None,
                next_request_id: 1,
                active_login: None,
                authenticated: false,
                pending_auth_refresh_observed: false,
                files_dir: None,
            }),
        })
    }

    pub fn start(&self, files_dir: PathBuf) -> Result<StartResult, RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        if inner.client.is_some() {
            return Ok(StartResult::ready());
        }
        let client = inner
            .runtime
            .block_on(start_client(files_dir.clone()))
            .map_err(|_| RuntimeError::Code(ErrorCode::RuntimeUnavailable))?;
        inner.client = Some(client);
        inner.files_dir = Some(files_dir);
        Ok(StartResult::ready())
    }

    pub fn begin_device_login(&self) -> Result<LoginResult, RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        if inner.client.is_none() {
            return Err(RuntimeError::Code(ErrorCode::NotReady));
        }
        if inner.active_login.is_some() {
            return Err(RuntimeError::Code(ErrorCode::LoginInProgress));
        }
        let id = RequestId::Integer(inner.next_request_id);
        inner.next_request_id = inner.next_request_id.saturating_add(1);
        let client = inner
            .client
            .as_ref()
            .ok_or(RuntimeError::Code(ErrorCode::NotReady))?;
        let result: LoginAccountResponse = inner
            .runtime
            .block_on(async {
                tokio::time::timeout(
                    REQUEST_TIMEOUT,
                    client.request_typed(ClientRequest::LoginAccount {
                        request_id: id,
                        params: LoginAccountParams::ChatgptDeviceCode,
                    }),
                )
                .await
                .map_err(|_| {
                    codex_app_server_client::TypedRequestError::Transport {
                        method: "account/login/start".to_string(),
                        source: std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            "request timeout",
                        ),
                    }
                })?
            })
            .map_err(|error| RuntimeError::Code(classify_login_start_error(&error)))?;
        let LoginAccountResponse::ChatgptDeviceCode {
            login_id,
            verification_url,
            user_code,
        } = result
        else {
            return Err(RuntimeError::Code(ErrorCode::LoginFailed));
        };
        if !verification_url.starts_with("https://") || user_code.is_empty() || login_id.is_empty()
        {
            return Err(RuntimeError::Code(ErrorCode::LoginFailed));
        }
        inner.active_login = Some(ActiveLogin {
            id: login_id,
            started: Instant::now(),
            completion_seen: false,
        });
        Ok(LoginResult::challenge(verification_url, user_code))
    }

    pub fn poll_login(&self) -> Result<&'static str, RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        let Some(active) = inner.active_login.as_ref() else {
            return if inner.authenticated {
                Ok("authenticated")
            } else {
                Err(RuntimeError::Code(ErrorCode::NotReady))
            };
        };
        let active_id = active.id.clone();
        if active.started.elapsed() >= LOGIN_TIMEOUT {
            if active.completion_seen {
                // A successful completion must not be cancelled merely
                // because its post-login AccountUpdated was delayed. Fail
                // closed without exposing an unauthenticated session.
                inner.active_login = None;
                return Err(RuntimeError::Code(ErrorCode::RuntimeUnavailable));
            }
            cancel_login(&mut inner, active_id)?;
            return Err(RuntimeError::Code(ErrorCode::LoginTimeout));
        }
        // Temporarily move the client out so the synchronous runtime borrow
        // cannot overlap a mutable borrow of the same `Inner` struct.
        let mut client = inner
            .client
            .take()
            .ok_or(RuntimeError::Code(ErrorCode::NotReady))?;
        let event = inner.runtime.block_on(async {
            tokio::time::timeout(Duration::from_millis(250), client.next_event()).await
        });
        inner.client = Some(client);
        match event {
            Ok(Some(InProcessServerEvent::ServerNotification(note))) => {
                let completion_seen = inner
                    .active_login
                    .as_ref()
                    .is_some_and(|active| active.completion_seen);
                if completion_seen && matches!(note.as_ref(), ServerNotification::AccountUpdated(_))
                {
                    inner.active_login = None;
                    inner.authenticated = true;
                    // A completed device login replaces the session; an
                    // event observed for the previous session cannot
                    // satisfy a future refresh gate.
                    inner.pending_auth_refresh_observed = false;
                    return Ok("authenticated");
                }
                if let ServerNotification::AccountLoginCompleted(completed) = note.as_ref()
                    && completed.login_id.as_deref() == Some(active_id.as_str())
                {
                    if completed.success {
                        if let Some(active) = inner.active_login.as_mut() {
                            active.completion_seen = true;
                        }
                        return Ok("waiting");
                    }
                    inner.active_login = None;
                    return Err(RuntimeError::Code(ErrorCode::LoginFailed));
                }
                Ok("waiting")
            }
            Ok(Some(InProcessServerEvent::ServerRequest(_))) => {
                Err(RuntimeError::Code(ErrorCode::RuntimeUnavailable))
            }
            Ok(Some(InProcessServerEvent::Lagged { .. })) => {
                Err(RuntimeError::Code(ErrorCode::RuntimeUnavailable))
            }
            Err(_) => Ok("waiting"),
            Ok(None) => Err(RuntimeError::Code(ErrorCode::RuntimeUnavailable)),
        }
    }

    pub fn read_rate_limits(&self) -> Result<RateLimitsResult, RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        // WorkManager may run while device login is active. Keep login events
        // exclusively on poll_login so the rate-limit pre-drain cannot consume
        // AccountLoginCompleted or its post-login AccountUpdated notification.
        if inner.active_login.is_some() {
            return Err(RuntimeError::Code(ErrorCode::LoginInProgress));
        }
        let id = RequestId::Integer(inner.next_request_id);
        inner.next_request_id = inner.next_request_id.saturating_add(1);
        let mut client = inner
            .client
            .take()
            .ok_or(RuntimeError::Code(ErrorCode::NotReady))?;
        let pending_before = inner.pending_auth_refresh_observed;
        let request_result = inner.runtime.block_on(async {
            // Events queued by login/logout or an earlier read are not
            // evidence for this request. Drain them before dispatching;
            // an unexpected server request is fail-closed rather than
            // silently discarded.
            drain_client_events(&mut client, Duration::from_millis(2)).await?;
            let response = match tokio::time::timeout(
                REQUEST_TIMEOUT,
                client.request_typed(ClientRequest::GetAccountRateLimits {
                    request_id: id,
                    params: None,
                }),
            )
            .await
            {
                Ok(response) => response,
                Err(_) => Err(codex_app_server_client::TypedRequestError::Transport {
                    method: "account/rateLimits/read".to_string(),
                    source: std::io::Error::new(std::io::ErrorKind::TimedOut, "request timeout"),
                }),
            };
            // The patched handler emits AccountUpdated before returning
            // its typed response. Observe only events that arrive after
            // the pre-request drain; retain the bit even on HTTP failure.
            let observed = observe_account_refresh(&mut client).await?;
            Ok((response, observed))
        });
        inner.client = Some(client);
        let (response, observed_during_request) = request_result.map_err(RuntimeError::Code)?;
        if observed_during_request {
            inner.pending_auth_refresh_observed = true;
        }
        let response =
            response.map_err(|_| RuntimeError::Code(ErrorCode::RateLimitsUnavailable))?;
        let auth_refresh_observed = pending_before || inner.pending_auth_refresh_observed;
        inner.pending_auth_refresh_observed = false;
        Ok(RateLimitsResult::from_snapshot(
            &response.rate_limits,
            auth_refresh_observed,
        ))
    }

    pub fn logout(&self) -> Result<(), RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        if inner.client.is_none() {
            return Err(RuntimeError::Code(ErrorCode::NotReady));
        }
        let id = RequestId::Integer(inner.next_request_id);
        inner.next_request_id = inner.next_request_id.saturating_add(1);
        let client = inner
            .client
            .as_ref()
            .ok_or(RuntimeError::Code(ErrorCode::NotReady))?;
        let _: codex_app_server_protocol::LogoutAccountResponse = inner
            .runtime
            .block_on(async {
                tokio::time::timeout(
                    REQUEST_TIMEOUT,
                    client.request_typed(ClientRequest::LogoutAccount {
                        request_id: id,
                        params: None,
                    }),
                )
                .await
                .map_err(|_| {
                    codex_app_server_client::TypedRequestError::Transport {
                        method: "account/logout".to_string(),
                        source: std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            "request timeout",
                        ),
                    }
                })?
            })
            .map_err(|_| RuntimeError::Code(ErrorCode::LogoutFailed))?;
        inner.active_login = None;
        inner.authenticated = false;
        inner.pending_auth_refresh_observed = false;
        Ok(())
    }

    pub fn shutdown(&self) -> Result<(), RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        if inner.client.is_none() {
            return Ok(());
        }

        // Keep cancellation private to the Rust facade.  The JNI boundary has
        // no cancel method, so shutdown must make a best-effort typed cancel
        // request before dropping the client.  Even if the request times out
        // or races with completion, continue with shutdown; the app-server
        // shutdown path also cancels its ActiveLogin token.
        if let Some(login_id) = inner
            .active_login
            .as_ref()
            .filter(|active| !active.completion_seen)
            .map(|active| active.id.clone())
        {
            let _ = cancel_login(&mut inner, login_id);
        }

        let client = inner
            .client
            .take()
            .ok_or(RuntimeError::Code(ErrorCode::NotReady))?;
        let shutdown_result = inner.runtime.block_on(client.shutdown());
        inner.active_login = None;
        inner.authenticated = false;
        inner.pending_auth_refresh_observed = false;
        shutdown_result.map_err(|_| RuntimeError::Code(ErrorCode::RuntimeUnavailable))
    }
}

/// Reduce a typed device-login-start failure to a stable, non-secret boundary
/// code.  The underlying transport/server text can contain URLs, account
/// details, or other implementation data, so it is deliberately never
/// returned or logged by this layer.
fn classify_login_start_error(error: &TypedRequestError) -> ErrorCode {
    match error {
        TypedRequestError::Transport { source, .. } => classify_login_start_transport(source),
        // The pinned app-server wraps device-code HTTP failures in a JSON-RPC
        // server error. Only its allowlisted category is consumed here;
        // provider message/source text is never used at the JNI boundary.
        TypedRequestError::Server { source, .. } => {
            classify_usage_ring_category(source.data.as_ref())
                .unwrap_or(ErrorCode::LoginStartServer)
        }
        TypedRequestError::Deserialize { .. } => ErrorCode::LoginStartDeserialize,
    }
}

fn classify_login_start_transport(source: &std::io::Error) -> ErrorCode {
    let detail = error_chain_detail(source);
    if has_tls_revocation_indicator(&detail) {
        return ErrorCode::LoginStartTlsRevoked;
    }
    if source.kind() == std::io::ErrorKind::TimedOut {
        return ErrorCode::LoginStartTimeout;
    }
    classify_login_start_detail(&detail).unwrap_or(ErrorCode::LoginStartTransport)
}

fn error_chain_detail(error: &dyn std::error::Error) -> String {
    let mut detail = error.to_string();
    let mut source = error.source();
    while let Some(current) = source {
        detail.push(' ');
        detail.push_str(&current.to_string());
        source = current.source();
    }
    detail
}

fn has_tls_revocation_indicator(detail: &str) -> bool {
    let detail = detail.to_ascii_lowercase();
    detail.contains("revoked")
        || detail.contains("revocation")
        || detail.contains("certpathvalidatorexception")
}

fn classify_usage_ring_category(data: Option<&Value>) -> Option<ErrorCode> {
    let category = data?.as_object()?.get("usageRingCategory")?.as_str()?;
    Some(match category {
        "not_supported" => ErrorCode::LoginStartNotSupported,
        "timeout" => ErrorCode::LoginStartTimeout,
        "tls_revoked" => ErrorCode::LoginStartTlsRevoked,
        "tls" => ErrorCode::LoginStartTls,
        "dns" => ErrorCode::LoginStartDns,
        "http_4xx" => ErrorCode::LoginStartHttp4xx,
        "http_5xx" => ErrorCode::LoginStartHttp5xx,
        "rate_limited" => ErrorCode::LoginStartRateLimited,
        "transport" => ErrorCode::LoginStartTransport,
        "unknown" => ErrorCode::LoginStartServer,
        _ => return None,
    })
}

fn classify_login_start_detail(detail: &str) -> Option<ErrorCode> {
    let detail = detail.to_ascii_lowercase();
    if has_tls_revocation_indicator(&detail) {
        return Some(ErrorCode::LoginStartTlsRevoked);
    }
    if detail.contains("timed out") || detail.contains("timeout") {
        return Some(ErrorCode::LoginStartTimeout);
    }
    if detail.contains("tls")
        || detail.contains("ssl")
        || detail.contains("certificate")
        || detail.contains("rustls")
        || detail.contains("handshake")
    {
        return Some(ErrorCode::LoginStartTls);
    }
    if detail.contains("dns")
        || detail.contains("getaddrinfo")
        || detail.contains("no such host")
        || detail.contains("name or service not known")
        || detail.contains("resolve")
    {
        return Some(ErrorCode::LoginStartDns);
    }
    if detail.contains("unsupported")
        || detail.contains("not supported")
        || detail.contains("not implemented")
        || detail.contains("disabled")
        || detail.contains("not enabled")
    {
        return Some(ErrorCode::LoginStartNotSupported);
    }
    None
}

fn cancel_login(inner: &mut Inner, login_id: String) -> Result<(), RuntimeError> {
    let id = RequestId::Integer(inner.next_request_id);
    inner.next_request_id = inner.next_request_id.saturating_add(1);
    let client = inner
        .client
        .as_ref()
        .ok_or(RuntimeError::Code(ErrorCode::NotReady))?;
    let _response: codex_app_server_protocol::CancelLoginAccountResponse = inner
        .runtime
        .block_on(async {
            tokio::time::timeout(
                REQUEST_TIMEOUT,
                client.request_typed(ClientRequest::CancelLoginAccount {
                    request_id: id,
                    params: CancelLoginAccountParams { login_id },
                }),
            )
            .await
            .map_err(|_| codex_app_server_client::TypedRequestError::Transport {
                method: "account/login/cancel".to_string(),
                source: std::io::Error::new(std::io::ErrorKind::TimedOut, "request timeout"),
            })?
        })
        .map_err(|_| RuntimeError::Code(ErrorCode::LoginTimeout))?;
    inner.active_login = None;
    Ok(())
}

/// Drain events already queued before a rate-limit request starts. The timeout
/// is deliberately short and bounded; this is a best-effort queue drain,
/// never a network wait or an auth operation. A server request is reported to
/// the caller so it cannot be silently discarded.
async fn drain_client_events(
    client: &mut InProcessAppServerClient,
    wait: Duration,
) -> Result<(), ErrorCode> {
    let deadline = tokio::time::Instant::now() + Duration::from_millis(25);
    for _ in 0..64 {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Ok(());
        }
        match tokio::time::timeout(wait.min(remaining), client.next_event()).await {
            Ok(Some(InProcessServerEvent::ServerRequest(_)))
            | Ok(Some(InProcessServerEvent::Lagged { .. })) => {
                return Err(ErrorCode::RuntimeUnavailable);
            }
            Ok(Some(_)) => continue,
            Ok(None) | Err(_) => return Ok(()),
        }
    }
    Err(ErrorCode::RuntimeUnavailable)
}

/// Observe only AccountUpdated notifications delivered after the request has
/// completed. The patched rate-limit handler emits this existing notification
/// when AuthManager's non-secret revision changes during auth acquisition.
async fn observe_account_refresh(client: &mut InProcessAppServerClient) -> Result<bool, ErrorCode> {
    let mut observed = false;
    let deadline = tokio::time::Instant::now() + Duration::from_millis(150);
    for _ in 0..64 {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Ok(observed);
        }
        let wait = if observed {
            Duration::from_millis(2)
        } else {
            // Allow the app-server worker to forward the notification that was
            // emitted immediately before the typed response.
            Duration::from_millis(100)
        };
        match tokio::time::timeout(wait.min(remaining), client.next_event()).await {
            Ok(Some(InProcessServerEvent::ServerNotification(note))) => {
                if matches!(note.as_ref(), ServerNotification::AccountUpdated(_)) {
                    observed = true;
                }
            }
            Ok(Some(InProcessServerEvent::ServerRequest(_))) => {
                return Err(ErrorCode::RuntimeUnavailable);
            }
            Ok(Some(InProcessServerEvent::Lagged { .. })) => {
                return Err(ErrorCode::RuntimeUnavailable);
            }
            Ok(None) | Err(_) => return Ok(observed),
        }
    }
    Err(ErrorCode::RuntimeUnavailable)
}

async fn start_client(files_dir: PathBuf) -> Result<InProcessAppServerClient, String> {
    if !PINNED_PLUGIN_STARTUP_PROVEN_SAFE {
        return Err("pinned in-process plugin startup is not forced to Skip".to_string());
    }
    let overrides = runtime_overrides();
    let config = ConfigBuilder::default()
        .codex_home(files_dir)
        .cli_overrides(overrides.clone())
        .strict_config(false)
        .build()
        .await
        .map_err(|err| err.to_string())?;
    validate_effective_config(&config)?;
    let config = Arc::new(config);
    InProcessAppServerClient::start(InProcessClientStartArgs {
        arg0_paths: Arg0DispatchPaths::default(),
        config,
        cli_overrides: overrides,
        loader_overrides: LoaderOverrides::default(),
        strict_config: false,
        cloud_config_bundle: CloudConfigBundleLoader::default(),
        feedback: CodexFeedback::new(),
        log_db: None,
        // Account login/rate-limit protocol methods do not require the
        // rollout state database. Keeping it absent avoids an unnecessary
        // SQLite runtime and its file/telemetry surface on Android.
        state_db: None,
        // No local execution environment is registered. Account operations
        // still use Codex's HTTPS client, but shell/filesystem/plugin hosts
        // have no environment to attach to.
        environment_manager: Arc::new(EnvironmentManager::without_environments(
            HttpClientFactory::new(OutboundProxyPolicy::ReqwestDefault),
        )),
        config_warnings: Vec::new(),
        session_source: SessionSource::Custom("usage-ring".to_string()),
        enable_codex_api_key_env: false,
        client_name: "usage-ring-android".to_string(),
        client_version: env!("CARGO_PKG_VERSION").to_string(),
        experimental_api: false,
        mcp_server_openai_form_elicitation: false,
        opt_out_notification_methods: vec![
            "item/agentMessage/delta".to_string(),
            "item/commandExecution/outputDelta".to_string(),
        ],
        channel_capacity: CHANNEL_CAPACITY,
    })
    .await
    .map_err(|err| err.to_string())
}

/// Validate the fully merged configuration after all user, managed, cloud,
/// and CLI layers have been applied. Empty table CLI overrides are recursive
/// merge no-ops in the upstream loader, so they cannot be treated as proof
/// that hostile lower-layer MCP/plugin settings disappeared.
fn validate_effective_config(config: &Config) -> Result<(), String> {
    let plugins_enabled = config.features.get().enabled(Feature::Plugins);
    let remote_plugins_enabled = config.features.get().enabled(Feature::RemotePlugin);
    let safe = effective_config_surface_is_safe(
        config.mcp_servers.get().is_empty(),
        plugins_enabled,
        remote_plugins_enabled,
        config.orchestrator_mcp_enabled,
        config.orchestrator_skills_enabled,
    );
    if safe {
        Ok(())
    } else {
        Err("effective Codex configuration enables a disallowed MCP, plugin, or orchestrator surface".to_string())
    }
}

fn effective_config_surface_is_safe(
    mcp_servers_empty: bool,
    plugins_enabled: bool,
    remote_plugins_enabled: bool,
    orchestrator_mcp_enabled: bool,
    orchestrator_skills_enabled: bool,
) -> bool {
    mcp_servers_empty
        && !plugins_enabled
        && !remote_plugins_enabled
        && !orchestrator_mcp_enabled
        && !orchestrator_skills_enabled
}

/// Request the narrowest account-only runtime settings at the CLI layer.
/// Empty tables here are recursive-merge no-ops; the fail-closed defense
/// against hostile user/managed/cloud config is the post-merge validation in
/// `validate_effective_config`, which rejects any effective MCP, plugin, or
/// orchestrator surface before startup.
fn runtime_overrides() -> Vec<(String, toml::Value)> {
    let empty = TomlMap::new();
    let mut features = TomlMap::new();
    features.insert("plugins".to_string(), toml::Value::Boolean(false));
    // `RemotePlugin` is a separate feature gate from local `plugins`; both
    // must be disabled in the actual pinned Codex feature table.
    features.insert("remote_plugin".to_string(), toml::Value::Boolean(false));
    let orchestrator_feature = || {
        toml::Value::Table(TomlMap::from_iter([(
            "enabled".to_string(),
            toml::Value::Boolean(false),
        )]))
    };
    // The pinned config shape is `[orchestrator.mcp]` and
    // `[orchestrator.skills]`; a flat `features.orchestrator_mcp` key is not
    // consumed by ConfigBuilder and therefore cannot disable these surfaces.
    let orchestrator = toml::Value::Table(TomlMap::from_iter([
        ("mcp".to_string(), orchestrator_feature()),
        ("skills".to_string(), orchestrator_feature()),
    ]));
    // The pinned config defaults metrics to Statsig.  Keep every OTEL route
    // disabled at the highest-precedence runtime layer, including hostile
    // user/managed endpoint settings and prompt logging.
    let mut otel = TomlMap::new();
    otel.insert("log_user_prompt".to_string(), toml::Value::Boolean(false));
    for key in ["exporter", "trace_exporter", "metrics_exporter"] {
        otel.insert(key.to_string(), toml::Value::String("none".to_string()));
    }
    vec![
        (
            "cli_auth_credentials_store".to_string(),
            toml::Value::String("file".to_string()),
        ),
        ("mcp_servers".to_string(), toml::Value::Table(empty)),
        ("plugins".to_string(), toml::Value::Table(TomlMap::new())),
        ("features".to_string(), toml::Value::Table(features)),
        ("orchestrator".to_string(), orchestrator),
        ("otel".to_string(), toml::Value::Table(otel)),
        (
            "analytics".to_string(),
            toml::Value::Table(TomlMap::from_iter([(
                "enabled".to_string(),
                toml::Value::Boolean(false),
            )])),
        ),
        (
            "feedback".to_string(),
            toml::Value::Table(TomlMap::from_iter([(
                "enabled".to_string(),
                toml::Value::Boolean(false),
            )])),
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn transport_error(kind: std::io::ErrorKind, message: &str) -> TypedRequestError {
        TypedRequestError::Transport {
            method: "account/login/start".to_string(),
            source: std::io::Error::new(kind, message),
        }
    }

    fn nested_transport_error(kind: std::io::ErrorKind, nested: &str) -> TypedRequestError {
        TypedRequestError::Transport {
            method: "account/login/start".to_string(),
            source: std::io::Error::new(kind, std::io::Error::other(nested)),
        }
    }

    fn server_error(message: &str) -> TypedRequestError {
        TypedRequestError::Server {
            method: "account/login/start".to_string(),
            source: codex_app_server_protocol::JSONRPCErrorError {
                code: -32000,
                data: None,
                message: message.to_string(),
            },
        }
    }

    fn categorized_server_error(category: &str) -> TypedRequestError {
        TypedRequestError::Server {
            method: "account/login/start".to_string(),
            source: codex_app_server_protocol::JSONRPCErrorError {
                code: -32603,
                data: Some(serde_json::json!({ "usageRingCategory": category })),
                message: "device login start failed: https://secret".to_string(),
            },
        }
    }

    #[test]
    fn login_start_error_classifier_is_stable_and_non_secret() {
        let cases = [
            (
                transport_error(
                    std::io::ErrorKind::TimedOut,
                    "request timeout https://secret",
                ),
                ErrorCode::LoginStartTimeout,
            ),
            (
                transport_error(
                    std::io::ErrorKind::Other,
                    "TLS certificate for secret.example",
                ),
                ErrorCode::LoginStartTls,
            ),
            (
                transport_error(
                    std::io::ErrorKind::Other,
                    "rustls-platform-verifier: CertPathValidatorException: certificate revoked",
                ),
                ErrorCode::LoginStartTlsRevoked,
            ),
            (
                nested_transport_error(
                    std::io::ErrorKind::Other,
                    "rustls-platform-verifier: CertPathValidatorException",
                ),
                ErrorCode::LoginStartTlsRevoked,
            ),
            (
                transport_error(std::io::ErrorKind::TimedOut, "TLS handshake timed out"),
                ErrorCode::LoginStartTimeout,
            ),
            (
                transport_error(
                    std::io::ErrorKind::Other,
                    "DNS lookup failed for secret.example",
                ),
                ErrorCode::LoginStartDns,
            ),
            (
                transport_error(std::io::ErrorKind::ConnectionReset, "connection reset"),
                ErrorCode::LoginStartTransport,
            ),
            (
                categorized_server_error("not_supported"),
                ErrorCode::LoginStartNotSupported,
            ),
            (categorized_server_error("tls"), ErrorCode::LoginStartTls),
            (
                categorized_server_error("tls_revoked"),
                ErrorCode::LoginStartTlsRevoked,
            ),
            (categorized_server_error("dns"), ErrorCode::LoginStartDns),
            (
                categorized_server_error("timeout"),
                ErrorCode::LoginStartTimeout,
            ),
            (
                categorized_server_error("not_supported"),
                ErrorCode::LoginStartNotSupported,
            ),
            (
                server_error("server rejected the request; account=secret"),
                ErrorCode::LoginStartServer,
            ),
        ];

        for (error, expected) in cases {
            assert_eq!(classify_login_start_error(&error), expected);
            assert!(!expected.as_str().contains("secret"));
        }

        let deserialize = TypedRequestError::Deserialize {
            method: "account/login/start".to_string(),
            source: serde_json::from_str::<LoginAccountResponse>("{not-json}").unwrap_err(),
        };
        assert_eq!(
            classify_login_start_error(&deserialize),
            ErrorCode::LoginStartDeserialize
        );
    }

    #[test]
    fn login_start_error_codes_have_explicit_wire_names() {
        assert_eq!(
            ErrorCode::LoginStartTransport.as_str(),
            "LOGIN_START_TRANSPORT"
        );
        assert_eq!(ErrorCode::LoginStartTls.as_str(), "LOGIN_START_TLS");
        assert_eq!(
            ErrorCode::LoginStartTlsRevoked.as_str(),
            "LOGIN_START_TLS_REVOKED"
        );
        assert_eq!(ErrorCode::LoginStartDns.as_str(), "LOGIN_START_DNS");
        assert_eq!(
            ErrorCode::LoginStartNotSupported.as_str(),
            "LOGIN_START_NOT_SUPPORTED"
        );
        assert_eq!(ErrorCode::LoginStartServer.as_str(), "LOGIN_START_SERVER");
        assert_eq!(
            ErrorCode::LoginStartHttp4xx.as_str(),
            "LOGIN_START_HTTP_4XX"
        );
        assert_eq!(
            ErrorCode::LoginStartHttp5xx.as_str(),
            "LOGIN_START_HTTP_5XX"
        );
        assert_eq!(
            ErrorCode::LoginStartRateLimited.as_str(),
            "LOGIN_START_RATE_LIMITED"
        );
        assert_eq!(
            ErrorCode::LoginStartDeserialize.as_str(),
            "LOGIN_START_DESERIALIZE"
        );
        assert_eq!(ErrorCode::LoginStartTimeout.as_str(), "LOGIN_START_TIMEOUT");
    }

    #[test]
    fn server_category_is_used_before_generic_json_rpc_message() {
        let cases = [
            ("not_supported", ErrorCode::LoginStartNotSupported),
            ("timeout", ErrorCode::LoginStartTimeout),
            ("tls", ErrorCode::LoginStartTls),
            ("tls_revoked", ErrorCode::LoginStartTlsRevoked),
            ("dns", ErrorCode::LoginStartDns),
            ("http_4xx", ErrorCode::LoginStartHttp4xx),
            ("http_5xx", ErrorCode::LoginStartHttp5xx),
            ("rate_limited", ErrorCode::LoginStartRateLimited),
            ("transport", ErrorCode::LoginStartTransport),
            ("unknown", ErrorCode::LoginStartServer),
        ];
        for (category, expected) in cases {
            assert_eq!(
                classify_login_start_error(&categorized_server_error(category)),
                expected
            );
        }
        assert_eq!(
            classify_login_start_error(&categorized_server_error("not-allowlisted")),
            ErrorCode::LoginStartServer
        );
    }

    #[test]
    fn hostile_runtime_overrides_are_disabled() {
        let values = runtime_overrides();
        assert!(values.iter().any(
            |(key, value)| key == "mcp_servers" && value == &toml::Value::Table(TomlMap::new())
        ));
        assert!(values.iter().any(|(key, value)| key == "features"
            && value.to_string().contains("plugins = false")
            && value.to_string().contains("remote_plugin = false")));
        let orchestrator = values
            .iter()
            .find_map(|(key, value)| (key == "orchestrator").then_some(value))
            .expect("orchestrator runtime override")
            .to_string();
        assert!(orchestrator.contains("mcp = { enabled = false }"));
        assert!(orchestrator.contains("skills = { enabled = false }"));
        let otel = values
            .iter()
            .find_map(|(key, value)| (key == "otel").then_some(value))
            .expect("OTEL runtime override");
        let otel_text = otel.to_string();
        assert!(otel_text.contains("log_user_prompt = false"));
        assert!(otel_text.contains("exporter = \"none\""));
        assert!(otel_text.contains("trace_exporter = \"none\""));
        assert!(otel_text.contains("metrics_exporter = \"none\""));
        assert!(values.iter().any(
            |(key, value)| key == "analytics" && value.to_string().contains("enabled = false")
        ));
        assert!(
            values
                .iter()
                .any(|(key, value)| key == "feedback"
                    && value.to_string().contains("enabled = false"))
        );
    }

    #[test]
    fn hostile_lower_layer_mcp_entry_is_rejected_after_recursive_merge() {
        let hostile_server = TomlMap::from_iter([(
            "command".to_string(),
            toml::Value::String("untrusted-mcp".to_string()),
        )]);
        let hostile_layer = TomlMap::from_iter([(
            "mcp_servers".to_string(),
            toml::Value::Table(TomlMap::from_iter([(
                "docs".to_string(),
                toml::Value::Table(hostile_server),
            )])),
        )]);
        let configured_mcp_entry = hostile_layer
            .get("mcp_servers")
            .and_then(toml::Value::as_table)
            .is_some_and(|servers| !servers.is_empty());
        assert!(configured_mcp_entry);
        assert!(!effective_config_surface_is_safe(
            !configured_mcp_entry,
            false,
            false,
            false,
            false,
        ));
        assert!(effective_config_surface_is_safe(
            true, false, false, false, false
        ));
    }

    #[test]
    fn config_builder_rejects_hostile_lower_layer_mcp_entry_after_merge() {
        let codex_home = std::env::temp_dir().join(format!(
            "usage-ring-hostile-config-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("system clock")
                .as_nanos()
        ));
        std::fs::create_dir_all(&codex_home).expect("temporary codex home");
        std::fs::write(
            codex_home.join("config.toml"),
            "[mcp_servers.docs]\ncommand = \"fake-hostile-server\"\n",
        )
        .expect("hostile config fixture");

        let build_result = Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("test runtime")
            .block_on(
                ConfigBuilder::default()
                    .codex_home(codex_home.clone())
                    .cli_overrides(runtime_overrides())
                    .loader_overrides(LoaderOverrides::without_managed_config_for_tests())
                    .strict_config(false)
                    .build(),
            );
        let _ = std::fs::remove_dir_all(&codex_home);
        let config = build_result.expect("hostile config should still parse");
        assert!(validate_effective_config(&config).is_err());
    }

    #[test]
    fn config_builder_accepts_account_only_defaults_with_nested_orchestrator_disabled() {
        let codex_home = std::env::temp_dir().join(format!(
            "usage-ring-default-config-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .expect("system clock")
                .as_nanos()
        ));
        std::fs::create_dir_all(&codex_home).expect("temporary codex home");

        let build_result = Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("test runtime")
            .block_on(
                ConfigBuilder::default()
                    .codex_home(codex_home.clone())
                    .cli_overrides(runtime_overrides())
                    .loader_overrides(LoaderOverrides::without_managed_config_for_tests())
                    .strict_config(false)
                    .build(),
            );
        let _ = std::fs::remove_dir_all(&codex_home);
        let config = build_result.expect("account-only defaults should parse");
        assert!(config.mcp_servers.is_empty());
        assert!(!config.features.get().enabled(Feature::Plugins));
        assert!(!config.features.get().enabled(Feature::RemotePlugin));
        assert!(!config.orchestrator_mcp_enabled);
        assert!(!config.orchestrator_skills_enabled);
        assert!(validate_effective_config(&config).is_ok());
    }

    #[test]
    fn plugin_and_orchestrator_features_are_rejected_even_without_mcp_entries() {
        for flags in [
            (true, false, false, false),
            (false, true, false, false),
            (false, false, true, false),
            (false, false, false, true),
        ] {
            assert!(!effective_config_surface_is_safe(
                true, flags.0, flags.1, flags.2, flags.3
            ));
        }
    }
}
