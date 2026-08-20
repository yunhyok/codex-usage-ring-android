//! Usage Ring native boundary.
//!
//! This crate is intentionally a *feasibility scaffold*.  It does not embed
//! the Codex runtime and it never performs HTTP, token, shell, tool, MCP, or
//! plugin operations.  Until the native gate proves an Android-compatible
//! `codex-app-server` in-process build, every operational method fails closed
//! with a stable, sanitized error.
//!
//! The public JNI surface is limited to the six methods in [`Method`].  Java
//! callers exchange JSON only; no Rust/Codex protocol type crosses JNI.

mod boundary;
mod jni_bridge;

pub use boundary::{ALLOWED_METHODS, ErrorCode, Method, dispatch_json};

/// Upstream source pin investigated for the in-process feasibility spike.
pub const UPSTREAM_TAG: &str = "rust-v0.148.0";
pub const UPSTREAM_COMMIT: &str = "3ba0f711642a888aec92a611a3f3b2211157ff89";
pub const UPSTREAM_TAG_OBJECT: &str = "ab52d1794d47d47ecaeb0ec37fc00fa31593ecf3";

// JNI exports are kept in a separate module so all JNI-to-JSON conversion is
// auditable in one place. The module is private because the C ABI symbols, not
// Rust function names, are the compatibility surface.
