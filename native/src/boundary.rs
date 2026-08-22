//! Closed, typed boundary between Kotlin and the in-process Codex client.
//!
//! The Android side can choose only the six operations below. It cannot
//! submit a JSON-RPC method, request id, token, shell command, plugin, MCP
//! server, or arbitrary protocol payload.

use serde::Serialize;
use serde_json::Value;
use std::fmt;
use std::path::PathBuf;
use std::sync::OnceLock;

use codex_app_server_protocol::{RateLimitSnapshot, RateLimitWindow};

use crate::runtime::RuntimeController;
use crate::{UPSTREAM_COMMIT, UPSTREAM_TAG};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Start,
    BeginDeviceLogin,
    PollLogin,
    ReadRateLimits,
    Logout,
    Shutdown,
}

pub const ALLOWED_METHODS: &[&str] = &[
    "start",
    "beginDeviceLogin",
    "pollLogin",
    "readRateLimits",
    "logout",
    "shutdown",
];

impl Method {
    pub fn parse(method: &str) -> Result<Self, ErrorCode> {
        match method {
            "start" => Ok(Self::Start),
            "beginDeviceLogin" => Ok(Self::BeginDeviceLogin),
            "pollLogin" => Ok(Self::PollLogin),
            "readRateLimits" => Ok(Self::ReadRateLimits),
            "logout" => Ok(Self::Logout),
            "shutdown" => Ok(Self::Shutdown),
            _ => Err(ErrorCode::MethodNotAllowed),
        }
    }
}

/// Stable, non-secret error classes exposed through JSON/JNI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    InvalidJson,
    InvalidRequest,
    SecretFieldRejected,
    MethodNotAllowed,
    NotReady,
    AlreadyShutdown,
    RuntimeUnavailable,
    LoginInProgress,
    LoginFailed,
    LoginStartTransport,
    LoginStartTls,
    LoginStartTlsRevoked,
    LoginStartDns,
    LoginStartNotSupported,
    LoginStartServer,
    LoginStartHttp4xx,
    LoginStartHttp5xx,
    LoginStartRateLimited,
    LoginStartDeserialize,
    LoginStartTimeout,
    LoginTimeout,
    RateLimitsUnavailable,
    LogoutFailed,
    JniError,
}

impl ErrorCode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidJson => "INVALID_JSON",
            Self::InvalidRequest => "INVALID_REQUEST",
            Self::SecretFieldRejected => "SECRET_FIELD_REJECTED",
            Self::MethodNotAllowed => "METHOD_NOT_ALLOWED",
            Self::NotReady => "NOT_READY",
            Self::AlreadyShutdown => "ALREADY_SHUTDOWN",
            Self::RuntimeUnavailable => "RUNTIME_UNAVAILABLE",
            Self::LoginInProgress => "LOGIN_IN_PROGRESS",
            Self::LoginFailed => "LOGIN_FAILED",
            Self::LoginStartTransport => "LOGIN_START_TRANSPORT",
            Self::LoginStartTls => "LOGIN_START_TLS",
            Self::LoginStartTlsRevoked => "LOGIN_START_TLS_REVOKED",
            Self::LoginStartDns => "LOGIN_START_DNS",
            Self::LoginStartNotSupported => "LOGIN_START_NOT_SUPPORTED",
            Self::LoginStartServer => "LOGIN_START_SERVER",
            Self::LoginStartHttp4xx => "LOGIN_START_HTTP_4XX",
            Self::LoginStartHttp5xx => "LOGIN_START_HTTP_5XX",
            Self::LoginStartRateLimited => "LOGIN_START_RATE_LIMITED",
            Self::LoginStartDeserialize => "LOGIN_START_DESERIALIZE",
            Self::LoginStartTimeout => "LOGIN_START_TIMEOUT",
            Self::LoginTimeout => "LOGIN_TIMEOUT",
            Self::RateLimitsUnavailable => "RATE_LIMITS_UNAVAILABLE",
            Self::LogoutFailed => "LOGOUT_FAILED",
            Self::JniError => "JNI_ERROR",
        }
    }
}

impl fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[derive(Debug, Serialize)]
struct ErrorBody {
    code: &'static str,
    message: &'static str,
}

#[derive(Debug, Serialize)]
struct Response<'a, T: Serialize> {
    ok: bool,
    method: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    result: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<ErrorBody>,
}

#[derive(Debug, Serialize)]
struct Metadata {
    implementation: &'static str,
    upstream_tag: &'static str,
    upstream_commit: &'static str,
    telemetry: bool,
    plugins: bool,
    mcp: bool,
    shell: bool,
    auth_refresh_supported: bool,
}

#[derive(Debug, Serialize)]
pub(crate) struct StartResult {
    status: &'static str,
    metadata: Metadata,
}

impl StartResult {
    pub(crate) const fn ready() -> Self {
        Self {
            status: "ready",
            metadata: Metadata {
                implementation: "codex-in-process",
                upstream_tag: UPSTREAM_TAG,
                upstream_commit: UPSTREAM_COMMIT,
                telemetry: false,
                plugins: false,
                mcp: false,
                shell: false,
                // Explicit forced auth refresh is not exposed through this
                // boundary; AuthManager proactive refresh remains internal.
                auth_refresh_supported: false,
            },
        }
    }
}

#[derive(Debug, Serialize)]
pub(crate) struct LoginResult {
    status: &'static str,
    verification_url: String,
    user_code: String,
}

impl LoginResult {
    pub(crate) fn challenge(verification_url: String, user_code: String) -> Self {
        Self {
            status: "waiting",
            verification_url,
            user_code,
        }
    }
}

#[derive(Debug, Serialize, Default, PartialEq, Eq)]
pub(crate) struct RateLimitsResult {
    status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    five_hour_used_percent: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    five_hour_reset_at_epoch_millis: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    five_hour_window_minutes: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    seven_day_used_percent: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    seven_day_reset_at_epoch_millis: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    seven_day_window_minutes: Option<i64>,
}

impl RateLimitsResult {
    pub(crate) fn from_snapshot(snapshot: &RateLimitSnapshot) -> Self {
        let mut result = Self {
            status: "ok",
            ..Self::default()
        };
        // Duration identifies named windows; primary/secondary are only a
        // compatibility fallback when duration is absent.
        if let Some(window) = snapshot.primary.as_ref() {
            result.apply(window, WindowKind::FiveHour);
        }
        if let Some(window) = snapshot.secondary.as_ref() {
            result.apply(window, WindowKind::SevenDay);
        }
        result
    }

    fn apply(&mut self, window: &RateLimitWindow, fallback: WindowKind) {
        if !(0..=100).contains(&window.used_percent) {
            return;
        }
        let Some(kind) = classify_window(window.window_duration_mins, fallback) else {
            // An explicit duration is authoritative.  Unknown, negative, or
            // otherwise extreme values must not be guessed into a named
            // window (the ordinal fallback is only for an absent duration).
            return;
        };
        let reset = window
            .resets_at
            .and_then(|seconds| seconds.checked_mul(1000));
        let (used, reset_at, duration) = match kind {
            WindowKind::FiveHour => (
                &mut self.five_hour_used_percent,
                &mut self.five_hour_reset_at_epoch_millis,
                &mut self.five_hour_window_minutes,
            ),
            WindowKind::SevenDay => (
                &mut self.seven_day_used_percent,
                &mut self.seven_day_reset_at_epoch_millis,
                &mut self.seven_day_window_minutes,
            ),
        };
        // Preserve the first valid window if malformed upstream data maps two
        // rows to the same named bucket.
        if used.is_none() {
            *used = Some(window.used_percent);
        }
        if reset_at.is_none() {
            *reset_at = reset;
        }
        if duration.is_none() {
            *duration = window.window_duration_mins;
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum WindowKind {
    FiveHour,
    SevenDay,
}

fn classify_window(duration: Option<i64>, fallback: WindowKind) -> Option<WindowKind> {
    match duration {
        None => Some(fallback),
        Some(300) => Some(WindowKind::FiveHour),
        Some(10_080) => Some(WindowKind::SevenDay),
        Some(_) => None,
    }
}

#[derive(Debug, Serialize)]
struct PollResult {
    status: &'static str,
}

#[derive(Debug, Serialize)]
struct EmptyResult {
    status: &'static str,
}

static CONTROLLER: OnceLock<Option<RuntimeController>> = OnceLock::new();

fn controller() -> Result<&'static RuntimeController, ErrorCode> {
    CONTROLLER
        .get_or_init(|| RuntimeController::new().ok())
        .as_ref()
        .ok_or(ErrorCode::RuntimeUnavailable)
}

/// Dispatch an allowlisted operation. `start` accepts exactly
/// `{ "filesDir": "...", "schemaVersion": 1 }`; all other methods accept
/// only `null` or `{}`. No request body is passed to Codex unchanged.
pub fn dispatch_json(method: &str, request_json: &str) -> String {
    let parsed_method = Method::parse(method);
    let method_for_response = if parsed_method.is_ok() {
        method
    } else {
        "unknown"
    };
    let method = match parsed_method {
        Ok(method) => method,
        Err(code) => return error_json(method_for_response, code),
    };
    let value: Value = match serde_json::from_str(request_json) {
        Ok(value) => value,
        Err(_) => return error_json(method_for_response, ErrorCode::InvalidJson),
    };
    if contains_secret_field(&value) {
        return error_json(method_for_response, ErrorCode::SecretFieldRejected);
    }

    if method == Method::Start {
        let Some(files_dir) = parse_start_request(&value) else {
            return error_json(method_for_response, ErrorCode::InvalidRequest);
        };
        return match controller().and_then(|runtime| runtime.start(files_dir).map_err(|e| e.code()))
        {
            Ok(result) => success_json(method_for_response, result),
            Err(code) => error_json(method_for_response, code),
        };
    }
    if !is_empty_request(&value) {
        return error_json(method_for_response, ErrorCode::InvalidRequest);
    }

    let result = match method {
        Method::BeginDeviceLogin => controller()
            .and_then(|runtime| runtime.begin_device_login().map_err(|e| e.code()))
            .map(|result| success_json(method_for_response, result)),
        Method::PollLogin => controller()
            .and_then(|runtime| runtime.poll_login().map_err(|e| e.code()))
            .map(|status| success_json(method_for_response, PollResult { status })),
        Method::ReadRateLimits => controller()
            .and_then(|runtime| runtime.read_rate_limits().map_err(|e| e.code()))
            .map(|result| success_json(method_for_response, result)),
        Method::Logout => controller()
            .and_then(|runtime| runtime.logout().map_err(|e| e.code()))
            .map(|_| success_json(method_for_response, EmptyResult { status: "ok" })),
        Method::Shutdown => controller()
            .and_then(|runtime| runtime.shutdown().map_err(|e| e.code()))
            .map(|_| success_json(method_for_response, EmptyResult { status: "stopped" })),
        Method::Start => unreachable!("start handled above"),
    };
    result.unwrap_or_else(|code| error_json(method_for_response, code))
}

fn parse_start_request(value: &Value) -> Option<PathBuf> {
    let Value::Object(map) = value else {
        return None;
    };
    if map.len() != 2 || map.get("schemaVersion")?.as_u64()? != 1 {
        return None;
    }
    let path = map.get("filesDir")?.as_str()?;
    if path.is_empty() || path.contains('\0') {
        return None;
    }
    let path = PathBuf::from(path);
    path.is_absolute().then_some(path)
}

fn success_json<T: Serialize>(method: &str, result: T) -> String {
    serde_json::to_string(&Response {
        ok: true,
        method,
        result: Some(result),
        error: None,
    })
    .unwrap_or_else(|_| error_json(method, ErrorCode::RuntimeUnavailable))
}

pub(crate) fn jni_error_json(method: &str) -> String {
    error_json(method, ErrorCode::JniError)
}

fn error_json(method: &str, code: ErrorCode) -> String {
    let message = match code {
        ErrorCode::InvalidJson => "request must be valid JSON",
        ErrorCode::InvalidRequest => "request shape is not accepted",
        ErrorCode::SecretFieldRejected => "secret-bearing fields are not accepted",
        ErrorCode::MethodNotAllowed => "method is not in the native allowlist",
        ErrorCode::NotReady => "native runtime has not been started",
        ErrorCode::AlreadyShutdown => "native runtime is already shut down",
        ErrorCode::RuntimeUnavailable => "Codex in-process runtime is unavailable",
        ErrorCode::LoginInProgress => "device login is already in progress",
        ErrorCode::LoginFailed => "device login failed",
        ErrorCode::LoginStartTransport => "device login transport failed",
        ErrorCode::LoginStartTls => "device login TLS verification failed",
        ErrorCode::LoginStartTlsRevoked => "device login TLS revocation verification failed",
        ErrorCode::LoginStartDns => "device login DNS resolution failed",
        ErrorCode::LoginStartNotSupported => "device login is not supported",
        ErrorCode::LoginStartServer => "device login server rejected the request",
        ErrorCode::LoginStartHttp4xx => "device login server returned a client error",
        ErrorCode::LoginStartHttp5xx => "device login server returned a server error",
        ErrorCode::LoginStartRateLimited => "device login request was rate limited",
        ErrorCode::LoginStartDeserialize => "device login response was invalid",
        ErrorCode::LoginStartTimeout => "device login start timed out",
        ErrorCode::LoginTimeout => "device login timed out",
        ErrorCode::RateLimitsUnavailable => "rate limits are unavailable",
        ErrorCode::LogoutFailed => "logout failed",
        ErrorCode::JniError => "JNI string conversion failed",
    };
    serde_json::to_string(&Response::<()> {
        ok: false,
        method,
        result: None,
        error: Some(ErrorBody {
            code: code.as_str(),
            message,
        }),
    })
    .unwrap_or_else(|_| {
        "{\"ok\":false,\"method\":\"unknown\",\"error\":{\"code\":\"RUNTIME_UNAVAILABLE\",\"message\":\"native boundary error\"}}".to_string()
    })
}

fn is_empty_request(value: &Value) -> bool {
    matches!(value, Value::Null) || matches!(value, Value::Object(map) if map.is_empty())
}

fn contains_secret_field(value: &Value) -> bool {
    match value {
        Value::Object(map) => map
            .iter()
            .any(|(key, child)| is_secret_key(key) || contains_secret_field(child)),
        Value::Array(items) => items.iter().any(contains_secret_field),
        _ => false,
    }
}

fn is_secret_key(key: &str) -> bool {
    let normalized = key
        .chars()
        .filter(|ch| ch.is_ascii_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect::<String>();
    const SECRET_KEYS: &[&str] = &[
        "access_token",
        "accesstoken",
        "api_key",
        "apikey",
        "authorization",
        "cookie",
        "credential",
        "credentials",
        "id_token",
        "idtoken",
        "jwt",
        "password",
        "private_key",
        "privatekey",
        "refresh_token",
        "refreshtoken",
        "secret",
        "secrets",
        "token",
        "tokens",
        "headers",
        "shell",
        "command",
        "tools",
        "mcp",
        "plugins",
        "rpc",
        "method",
    ];
    SECRET_KEYS.iter().any(|candidate| {
        let normalized_candidate = candidate
            .chars()
            .filter(|ch| ch.is_ascii_alphanumeric())
            .flat_map(char::to_lowercase)
            .collect::<String>();
        normalized == normalized_candidate
            || normalized.contains(&normalized_candidate)
            || normalized_candidate.contains(&normalized)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use pretty_assertions::assert_eq;

    #[test]
    fn allowlist_is_exact_and_complete() {
        assert_eq!(
            ALLOWED_METHODS,
            [
                "start",
                "beginDeviceLogin",
                "pollLogin",
                "readRateLimits",
                "logout",
                "shutdown"
            ]
        );
        assert_eq!(
            Method::parse("account/rateLimits/read"),
            Err(ErrorCode::MethodNotAllowed)
        );
        assert_eq!(Method::parse("exec"), Err(ErrorCode::MethodNotAllowed));
    }

    #[test]
    fn tls_revoked_message_is_neutral_about_certificate_state() {
        let response = error_json("beginDeviceLogin", ErrorCode::LoginStartTlsRevoked);
        assert!(response.contains("TLS revocation verification failed"));
        assert!(!response.contains("revoked"));
    }

    #[test]
    fn start_requires_files_dir_and_schema() {
        assert_eq!(
            serde_json::from_str::<Value>(&dispatch_json("start", "null")).unwrap()["error"]["code"],
            "INVALID_REQUEST"
        );
        assert_eq!(
            serde_json::from_str::<Value>(&dispatch_json("start", "{}")).unwrap()["error"]["code"],
            "INVALID_REQUEST"
        );
        let response = dispatch_json("start", r#"{"filesDir":"relative","schemaVersion":1}"#);
        assert_eq!(
            serde_json::from_str::<Value>(&response).unwrap()["error"]["code"],
            "INVALID_REQUEST"
        );
    }

    #[test]
    fn nested_secret_fields_are_rejected_without_echoing_values() {
        for request in [
            r#"{"token":"do-not-echo"}"#,
            r#"{"options":{"Authorization":"Bearer do-not-echo"}}"#,
            r#"{"nested":[{"api_key":"do-not-echo"}]}"#,
        ] {
            let response = dispatch_json("start", request);
            assert_eq!(
                serde_json::from_str::<Value>(&response).unwrap()["error"]["code"],
                "SECRET_FIELD_REJECTED"
            );
            assert!(!response.contains("do-not-echo"));
        }
    }

    fn window(used_percent: i32, duration: Option<i64>, reset: Option<i64>) -> RateLimitWindow {
        RateLimitWindow {
            used_percent,
            window_duration_mins: duration,
            resets_at: reset,
        }
    }

    fn snapshot(
        primary: Option<RateLimitWindow>,
        secondary: Option<RateLimitWindow>,
    ) -> RateLimitSnapshot {
        RateLimitSnapshot {
            limit_id: Some("secret-id-must-not-leak".into()),
            limit_name: None,
            primary,
            secondary,
            credits: None,
            individual_limit: None,
            spend_control_reached: None,
            plan_type: None,
            rate_limit_reached_type: None,
        }
    }

    #[test]
    fn nested_rate_limit_windows_are_named_and_converted_to_millis() {
        let result = RateLimitsResult::from_snapshot(&snapshot(
            Some(window(42, Some(300), Some(1_700_000_000))),
            Some(window(7, Some(10_080), None)),
        ));
        assert_eq!(result.five_hour_used_percent, Some(42));
        assert_eq!(
            result.five_hour_reset_at_epoch_millis,
            Some(1_700_000_000_000)
        );
        assert_eq!(result.five_hour_window_minutes, Some(300));
        assert_eq!(result.seven_day_used_percent, Some(7));
        assert_eq!(result.seven_day_reset_at_epoch_millis, None);
        assert_eq!(result.seven_day_window_minutes, Some(10_080));
        let encoded = serde_json::to_string(&result).unwrap();
        assert!(!encoded.contains("secret-id"));
        assert!(!encoded.contains("primary"));
    }

    #[test]
    fn sparse_null_and_invalid_windows_stay_absent() {
        let result =
            RateLimitsResult::from_snapshot(&snapshot(Some(window(101, Some(300), Some(1))), None));
        assert_eq!(
            result,
            RateLimitsResult {
                status: "ok",
                ..Default::default()
            }
        );
    }

    #[test]
    fn absent_duration_uses_ordinal_only_as_fallback() {
        let result = RateLimitsResult::from_snapshot(&snapshot(
            Some(window(10, None, None)),
            Some(window(20, None, None)),
        ));
        assert_eq!(result.five_hour_used_percent, Some(10));
        assert_eq!(result.seven_day_used_percent, Some(20));
    }

    #[test]
    fn unknown_explicit_duration_is_omitted_without_ordinal_guessing() {
        let result = RateLimitsResult::from_snapshot(&snapshot(
            Some(window(10, Some(301), None)),
            Some(window(20, Some(-1), None)),
        ));
        assert_eq!(result.five_hour_used_percent, None);
        assert_eq!(result.seven_day_used_percent, None);
    }

    #[test]
    fn extreme_explicit_duration_is_omitted_without_overflow_or_guessing() {
        let result = RateLimitsResult::from_snapshot(&snapshot(
            Some(window(10, Some(i64::MAX), None)),
            Some(window(20, Some(i64::MIN), None)),
        ));
        assert_eq!(result.five_hour_used_percent, None);
        assert_eq!(result.seven_day_used_percent, None);
    }
}
