//! Small, closed controller around the pinned in-process app-server facade.
//!
//! This module is deliberately the only place that imports Codex protocol
//! types.  JNI callers can select one of six operations, but cannot provide a
//! wire method, a request id, credentials, shell command, plugin, or MCP
//! configuration.  All calls run on one bounded Tokio runtime and every
//! response is reduced to the DTOs in `boundary.rs`.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, Instant};

use codex_app_server_client::legacy_core::config::ConfigBuilder;
use codex_app_server_client::{
    EnvironmentManager, InProcessAppServerClient, InProcessClientStartArgs, InProcessServerEvent,
};
use codex_app_server_protocol::{
    CancelLoginAccountParams, ClientRequest, GetAccountRateLimitsResponse, LoginAccountParams,
    LoginAccountResponse, RequestId, ServerNotification,
};
use codex_arg0::Arg0DispatchPaths;
use codex_config::{CloudConfigBundleLoader, LoaderOverrides};
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
}

struct Inner {
    runtime: Runtime,
    client: Option<InProcessAppServerClient>,
    next_request_id: i64,
    active_login: Option<ActiveLogin>,
    authenticated: bool,
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
            .map_err(|_| RuntimeError::Code(ErrorCode::LoginFailed))?;
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
                if let ServerNotification::AccountLoginCompleted(completed) = note.as_ref()
                    && completed.login_id.as_deref() == Some(active_id.as_str())
                {
                    let success = completed.success;
                    inner.active_login = None;
                    inner.authenticated = success;
                    return if success {
                        Ok("authenticated")
                    } else {
                        Err(RuntimeError::Code(ErrorCode::LoginFailed))
                    };
                }
                Ok("waiting")
            }
            Ok(Some(InProcessServerEvent::ServerRequest(_))) => {
                Err(RuntimeError::Code(ErrorCode::RuntimeUnavailable))
            }
            Ok(Some(InProcessServerEvent::Lagged { .. })) | Err(_) => Ok("waiting"),
            Ok(None) => Err(RuntimeError::Code(ErrorCode::RuntimeUnavailable)),
        }
    }

    pub fn read_rate_limits(&self) -> Result<RateLimitsResult, RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        let id = RequestId::Integer(inner.next_request_id);
        inner.next_request_id = inner.next_request_id.saturating_add(1);
        let client = inner
            .client
            .as_ref()
            .ok_or(RuntimeError::Code(ErrorCode::NotReady))?;
        let response: GetAccountRateLimitsResponse = inner
            .runtime
            .block_on(async {
                tokio::time::timeout(
                    REQUEST_TIMEOUT,
                    client.request_typed(ClientRequest::GetAccountRateLimits {
                        request_id: id,
                        params: None,
                    }),
                )
                .await
                .map_err(|_| {
                    codex_app_server_client::TypedRequestError::Transport {
                        method: "account/rateLimits/read".to_string(),
                        source: std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            "request timeout",
                        ),
                    }
                })?
            })
            .map_err(|_| RuntimeError::Code(ErrorCode::RateLimitsUnavailable))?;
        Ok(RateLimitsResult::from_snapshot(&response.rate_limits))
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
        Ok(())
    }

    pub fn shutdown(&self) -> Result<(), RuntimeError> {
        let mut inner = self.inner.lock().map_err(|_| RuntimeError::Message)?;
        let Some(client) = inner.client.take() else {
            return Ok(());
        };
        inner
            .runtime
            .block_on(client.shutdown())
            .map_err(|_| RuntimeError::Code(ErrorCode::RuntimeUnavailable))?;
        inner.active_login = None;
        inner.authenticated = false;
        Ok(())
    }
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

async fn start_client(files_dir: PathBuf) -> Result<InProcessAppServerClient, String> {
    if !PINNED_PLUGIN_STARTUP_PROVEN_SAFE {
        return Err("pinned in-process plugin startup is not forced to Skip".to_string());
    }
    let overrides = runtime_overrides();
    let config = Arc::new(
        ConfigBuilder::default()
            .codex_home(files_dir)
            .cli_overrides(overrides.clone())
            .strict_config(false)
            .build()
            .await
            .map_err(|err| err.to_string())?,
    );
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

/// CLI overrides are applied after every user/managed config layer. This is
/// the fail-closed defense against hostile `config.toml`: no MCP server,
/// plugin feature, orchestrator MCP, or API-key environment variable can turn
/// on a process/tool surface for this controller.
fn runtime_overrides() -> Vec<(String, toml::Value)> {
    let empty = TomlMap::new();
    let mut features = TomlMap::new();
    features.insert("plugins".to_string(), toml::Value::Boolean(false));
    features.insert("orchestrator_mcp".to_string(), toml::Value::Boolean(false));
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

    #[test]
    fn hostile_runtime_overrides_are_disabled() {
        let values = runtime_overrides();
        assert!(values.iter().any(
            |(key, value)| key == "mcp_servers" && value == &toml::Value::Table(TomlMap::new())
        ));
        assert!(
            values
                .iter()
                .any(|(key, value)| key == "features"
                    && value.to_string().contains("plugins = false"))
        );
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
}
