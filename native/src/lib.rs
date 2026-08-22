//! Usage Ring native boundary.
//!
//! This crate embeds the pinned Codex app-server in-process facade behind a
//! deliberately closed JNI boundary. Device-code challenges and sanitized
//! rate-limit windows are the only account data exposed to Kotlin.
//!
//! The public JNI surface is limited to the six methods in [`Method`].  Java
//! callers exchange JSON only; no Rust/Codex protocol type crosses JNI.

mod boundary;
mod jni_bridge;
mod runtime;

pub use boundary::{ALLOWED_METHODS, ErrorCode, Method, dispatch_json};

/// Upstream source pin investigated for the in-process feasibility spike.
pub const UPSTREAM_TAG: &str = "rust-v0.148.0";
pub const UPSTREAM_COMMIT: &str = "3ba0f711642a888aec92a611a3f3b2211157ff89";
pub const UPSTREAM_TAG_OBJECT: &str = "ab52d1794d47d47ecaeb0ec37fc00fa31593ecf3";

/// Machine-readable evidence that the packaged library contains the pinned
/// in-process implementation. The gate inspects this exported symbol so a
/// stale scaffold `.so` cannot silently satisfy dependency-only checks.
const RUNTIME_MARKER: &[u8] =
    b"usage-ring:codex-in-process:rust-v0.148.0:3ba0f711642a888aec92a611a3f3b2211157ff89:plugin-patch-sha256=31c11d95092364ba25d5748390252211b2ceb6fa8444d8ff6bfe81c48bc8d572:android-installation-id-patch-sha256=3922b9110aee8a3e7326c3c0ce8dd3ff36881802d9dfa9290ac85e9da789b8f6:device-login-error-patch-sha256=14b1b07f07912f0178bd37dbc6d49a8001f9e76cb333abb524236b73cbfa5714:auth-refresh-evidence-patch-sha256=a02c6cd717d1d81c2eca2590c9f87b606887fc54c536160f6f0cffd345717029:app-server-cargo-manifest-sha256=dda0d9d99cb84fbcd10d63a72757fc1677b44a8f7736f3d190dc3bf0c486650d:app-server-source-tree-sha256=a0ee958fbbff4b0ad7a1626e588a7f1e143bfde576028fa7e096d3863d182c20:app-server-in-process-sha256=89250f5ef5dc3501d9bc5768ac6f43477ba36cb2c3e48eefb23c80ff51c4fede:app-server-error-code-sha256=74ab810d12a116928e5c6af69e36e80d0090be6107fbadc3f30b2ee07905636c:app-server-account-processor-sha256=491e915fdfe580acbcfee3532ce16c8b3c26c18fdfc996e25e7b2d62fd14e1a0:gix-manifest-sha256=44c57496572f5e75382398a7d2bdd9d7898b28e4043917b71ad7cdd0ed0f279a:gix-source-tree-sha256=612b653c5725b1285076a9f8a27461f16464d69cc97a82458c1b790b1ceffa15:dns-manifest-sha256=7ac309c4323860cbc77c37ea0cd82aaa62d3716b6e8312b7ca7c4cce5c40d4a7:dns-source-sha256=9518b743adddbc5a0d54b7587f9d712cf575fe8fad23959c78ca1475016dabf9:telemetry=false:plugins=false:mcp=false:shell=false\0";

#[unsafe(no_mangle)]
pub extern "C" fn usage_ring_codex_runtime_marker() -> *const u8 {
    RUNTIME_MARKER.as_ptr()
}

// JNI exports are kept in a separate module so all JNI-to-JSON conversion is
// auditable in one place. The module is private because the C ABI symbols, not
// Rust function names, are the compatibility surface.
