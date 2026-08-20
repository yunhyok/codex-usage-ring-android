use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

use crate::boundary::dispatch_json;

/// Convert one Java string to a Rust string without exposing JNI exceptions or
/// a panic across the C ABI.  A null request is treated as JSON `null`.
fn request_string(env: &mut JNIEnv<'_>, request: JString<'_>) -> String {
    if request.is_null() {
        return "null".to_string();
    }
    env.get_string(&request)
        .map(|value| value.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "null".to_string())
}

fn response_string(env: &mut JNIEnv<'_>, response: String) -> jstring {
    env.new_string(response)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Java package/class contract for the eventual Android wrapper:
/// `io.github.yunhyok.usagering.data.NativeCodexBridgeNative`.
///
/// Keep this list synchronized with `ALLOWED_METHODS`; there are intentionally
/// no JNI exports for arbitrary RPC, shell, tools, MCP, plugins, or tokens.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_start(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let request = request_string(&mut env, request);
    response_string(&mut env, dispatch_json("start", &request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_beginDeviceLogin(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let request = request_string(&mut env, request);
    response_string(&mut env, dispatch_json("beginDeviceLogin", &request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_pollLogin(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let request = request_string(&mut env, request);
    response_string(&mut env, dispatch_json("pollLogin", &request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_readRateLimits(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let request = request_string(&mut env, request);
    response_string(&mut env, dispatch_json("readRateLimits", &request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_logout(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let request = request_string(&mut env, request);
    response_string(&mut env, dispatch_json("logout", &request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_shutdown(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    let request = request_string(&mut env, request);
    response_string(&mut env, dispatch_json("shutdown", &request))
}
