use serde::Serialize;
use serde_json::Value;
use std::fmt;

use crate::{UPSTREAM_COMMIT, UPSTREAM_TAG};

/// The only operations that may be requested by the Android side.
///
/// These names intentionally differ from app-server's wire method names: the
/// Android caller cannot submit arbitrary JSON-RPC method names.
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
struct Metadata<'a> {
    implementation: &'a str,
    upstream_tag: &'a str,
    upstream_commit: &'a str,
}

#[derive(Debug, Serialize)]
struct StartResult<'a> {
    status: &'a str,
    metadata: Metadata<'a>,
}

#[derive(Debug, Serialize)]
struct PollResult<'a> {
    status: &'a str,
}

#[derive(Debug, Serialize)]
struct RateLimitsResult<'a> {
    status: &'a str,
    /// No values are fabricated while the upstream runtime is unavailable.
    #[serde(skip_serializing_if = "Option::is_none")]
    primary_used_percent: Option<u8>,
    #[serde(skip_serializing_if = "Option::is_none")]
    secondary_used_percent: Option<u8>,
}

#[derive(Debug, Serialize)]
struct EmptyResult<'a> {
    status: &'a str,
}

/// Dispatch one allowlisted method after validating a deliberately tiny JSON
/// request.  `null` and `{}` are the only accepted request bodies today.
/// This keeps the JNI boundary closed while the upstream integration is
/// unproven; in particular, no caller-supplied token or raw RPC can pass.
pub fn dispatch_json(method: &str, request_json: &str) -> String {
    let parsed_method = Method::parse(method);
    // Never reflect an untrusted method string.  JNI exports pass constants,
    // but keeping the Rust boundary closed also protects direct callers and
    // prevents a secret-shaped method argument from appearing in the DTO.
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

    if !is_empty_request(&value) {
        return error_json(method_for_response, ErrorCode::InvalidRequest);
    }

    match method {
        Method::Start => success_json(
            method_for_response,
            StartResult {
                status: "scaffold",
                metadata: Metadata {
                    implementation: "jni-scaffold",
                    upstream_tag: UPSTREAM_TAG,
                    upstream_commit: UPSTREAM_COMMIT,
                },
            },
        ),
        Method::BeginDeviceLogin => error_json(method_for_response, ErrorCode::NotReady),
        Method::PollLogin => success_json(
            method_for_response,
            PollResult {
                status: "unavailable",
            },
        ),
        Method::ReadRateLimits => success_json(
            method_for_response,
            RateLimitsResult {
                status: "unavailable",
                primary_used_percent: None,
                secondary_used_percent: None,
            },
        ),
        Method::Logout => error_json(method_for_response, ErrorCode::NotReady),
        Method::Shutdown => success_json(method_for_response, EmptyResult { status: "scaffold" }),
    }
}

fn success_json<T: Serialize>(method: &str, result: T) -> String {
    // Serialization of these in-memory structs cannot fail.  Keep a fallback
    // anyway so a future DTO change never leaks a panic across JNI.
    serde_json::to_string(&Response {
        ok: true,
        method,
        result: Some(result),
        error: None,
    })
    .unwrap_or_else(|_| error_json(method, ErrorCode::InvalidRequest))
}

fn error_json(method: &str, code: ErrorCode) -> String {
    let message = match code {
        ErrorCode::InvalidJson => "request must be valid JSON",
        ErrorCode::InvalidRequest => "request must be null or an empty JSON object",
        ErrorCode::SecretFieldRejected => "secret-bearing fields are not accepted",
        ErrorCode::MethodNotAllowed => "method is not in the native allowlist",
        ErrorCode::NotReady => "Codex in-process runtime is not Android-ready",
        ErrorCode::AlreadyShutdown => "native runtime is already shut down",
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
        // This literal contains no user input and is therefore safe as a last
        // resort even if the response DTO changes unexpectedly.
        "{\"ok\":false,\"method\":\"unknown\",\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"native boundary error\"}}".to_string()
    })
}

fn is_empty_request(value: &Value) -> bool {
    match value {
        Value::Null => true,
        Value::Object(map) => map.is_empty(),
        _ => false,
    }
}

/// Reject key names that are commonly used for credentials or arbitrary
/// transport escape hatches.  The check is recursive so a secret nested in an
/// otherwise innocuous object cannot cross the boundary.
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
    use serde_json::Value;

    fn parse(response: &str) -> Value {
        serde_json::from_str(response).expect("valid response JSON")
    }

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
                "shutdown",
            ]
        );
        for method in ALLOWED_METHODS {
            assert!(Method::parse(method).is_ok(), "{method}");
        }
        assert_eq!(
            Method::parse("account/rateLimits/read"),
            Err(ErrorCode::MethodNotAllowed)
        );
        assert_eq!(Method::parse("exec"), Err(ErrorCode::MethodNotAllowed));
        assert_eq!(
            Method::parse("mcpServer/oauth/login"),
            Err(ErrorCode::MethodNotAllowed)
        );
    }

    #[test]
    fn null_and_empty_object_are_the_only_requests() {
        assert_eq!(parse(&dispatch_json("start", "null"))["ok"], true);
        assert_eq!(parse(&dispatch_json("start", "{}"))["ok"], true);
        assert_eq!(
            parse(&dispatch_json("start", "[]"))["error"]["code"],
            "INVALID_REQUEST"
        );
        assert_eq!(
            parse(&dispatch_json("start", "{\"x\":1}"))["error"]["code"],
            "INVALID_REQUEST"
        );
    }

    #[test]
    fn nested_secret_fields_are_rejected_without_echoing_values() {
        for request in [
            r#"{"token":"do-not-echo"}"#,
            r#"{"options":{"Authorization":"Bearer do-not-echo"}}"#,
            r#"{"nested":[{"api_key":"do-not-echo"}]}"#,
            r#"{"mcpServer":{"command":"do-not-echo"}}"#,
        ] {
            let response = dispatch_json("start", request);
            assert_eq!(parse(&response)["error"]["code"], "SECRET_FIELD_REJECTED");
            assert!(!response.contains("do-not-echo"));
        }
    }

    #[test]
    fn responses_are_sanitized_and_do_not_contain_secret_shaped_fields() {
        let start = dispatch_json("start", "{}");
        assert!(start.contains("upstream_tag"));
        for forbidden in ["token", "api_key", "authorization", "password", "secret"] {
            assert!(!start.contains(forbidden), "response contains {forbidden}");
        }
        let limits = dispatch_json("readRateLimits", "{}");
        assert_eq!(parse(&limits)["result"]["status"], "unavailable");
        assert!(
            parse(&limits)["result"]
                .get("primary_used_percent")
                .is_none()
        );
    }

    #[test]
    fn disallowed_method_does_not_echo_method_or_request() {
        let response = dispatch_json("api_key_secret", r#"{"token":"do-not-echo"}"#);
        let parsed = parse(&response);
        assert_eq!(parsed["error"]["code"], "METHOD_NOT_ALLOWED");
        assert_eq!(parsed["method"], "unknown");
        assert!(!response.contains("api_key_secret"));
        assert!(!response.contains("do-not-echo"));
    }
}
