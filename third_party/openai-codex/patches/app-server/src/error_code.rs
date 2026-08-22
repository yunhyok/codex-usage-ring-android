use codex_app_server_protocol::JSONRPCErrorError;
use serde_json::json;

pub(crate) const INVALID_REQUEST_ERROR_CODE: i64 = -32600;
pub(crate) const METHOD_NOT_FOUND_ERROR_CODE: i64 = -32601;
pub const INVALID_PARAMS_ERROR_CODE: i64 = -32602;
pub(crate) const INTERNAL_ERROR_CODE: i64 = -32603;
pub(crate) const OVERLOADED_ERROR_CODE: i64 = -32001;
pub const INPUT_TOO_LARGE_ERROR_CODE: &str = "input_too_large";

/// The only provider-specific detail allowed to cross the Android app-server
/// boundary.  Keep this list small and stable: callers must never receive
/// transport text, URLs, identifiers, response bodies, or tokens.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum DeviceCodeStartErrorCategory {
    NotSupported,
    Timeout,
    TlsRevoked,
    Tls,
    Dns,
    Http4xx,
    Http5xx,
    RateLimited,
    Transport,
    Unknown,
}

impl DeviceCodeStartErrorCategory {
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::NotSupported => "not_supported",
            Self::Timeout => "timeout",
            Self::TlsRevoked => "tls_revoked",
            Self::Tls => "tls",
            Self::Dns => "dns",
            Self::Http4xx => "http_4xx",
            Self::Http5xx => "http_5xx",
            Self::RateLimited => "rate_limited",
            Self::Transport => "transport",
            Self::Unknown => "unknown",
        }
    }
}

pub(crate) fn device_code_start_error(category: DeviceCodeStartErrorCategory) -> JSONRPCErrorError {
    JSONRPCErrorError {
        code: INTERNAL_ERROR_CODE,
        data: Some(json!({ "usageRingCategory": category.as_str() })),
        message: "device login start failed".to_string(),
    }
}

pub(crate) fn invalid_request(message: impl Into<String>) -> JSONRPCErrorError {
    error(INVALID_REQUEST_ERROR_CODE, message)
}

pub(crate) fn method_not_found(message: impl Into<String>) -> JSONRPCErrorError {
    error(METHOD_NOT_FOUND_ERROR_CODE, message)
}

pub(crate) fn invalid_params(message: impl Into<String>) -> JSONRPCErrorError {
    error(INVALID_PARAMS_ERROR_CODE, message)
}

pub(crate) fn internal_error(message: impl Into<String>) -> JSONRPCErrorError {
    error(INTERNAL_ERROR_CODE, message)
}

fn error(code: i64, message: impl Into<String>) -> JSONRPCErrorError {
    JSONRPCErrorError {
        code,
        message: message.into(),
        data: None,
    }
}
