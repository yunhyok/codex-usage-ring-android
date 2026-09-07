use jni::objects::{JClass, JObject, JString};
use jni::sys::jstring;
use jni::{Env, EnvUnowned, Outcome};

use crate::boundary::{dispatch_json, jni_error_json};

/// Run JNI work through `EnvUnowned::with_env`, which catches Rust panics so
/// no unwind can cross the Java ABI. Conversion and verifier failures become a
/// sanitized JSON error; pending Java exceptions are cleared explicitly.
fn with_jni(mut unowned: EnvUnowned<'_>, f: impl FnOnce(&mut Env<'_>) -> jstring) -> jstring {
    let outcome = unowned.with_env(|env| Ok::<jstring, jni::errors::Error>(f(env)));
    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

fn request_string(env: &mut Env<'_>, request: JString<'_>) -> Result<String, ()> {
    if request.is_null() {
        return Ok("null".to_string());
    }
    request
        .mutf8_chars(env)
        .map(|value| value.to_string())
        .map_err(|_| {
            env.exception_clear();
        })
}

fn response_string(env: &mut Env<'_>, response: String) -> jstring {
    env.new_string(response)
        .map(|value| value.into_raw())
        .unwrap_or_else(|_| {
            env.exception_clear();
            std::ptr::null_mut()
        })
}

fn dispatch_from_jni(env: &mut Env<'_>, method: &'static str, request: JString<'_>) -> jstring {
    let response = request_string(env, request)
        .map(|request| dispatch_json(method, &request))
        .unwrap_or_else(|_| jni_error_json(method));
    response_string(env, response)
}

/// Android's TLS verifier must be initialized with the application Context so
/// rustls can call the platform TrustManager. Other targets do not need this.
#[cfg(target_os = "android")]
fn init_platform_verifier(env: &mut Env<'_>, context: JObject<'_>) -> Result<(), ()> {
    if context.is_null() {
        return Err(());
    }
    rustls_platform_verifier::android::init_with_env(env, context).map_err(|_| {
        env.exception_clear();
    })
}

#[cfg(not(target_os = "android"))]
fn init_platform_verifier(_env: &mut Env<'_>, _context: JObject<'_>) -> Result<(), ()> {
    Ok(())
}

/// Java package/class contract for the Android wrapper:
/// `io.github.yunhyok.usagering.data.NativeCodexBridgeNative`.
///
/// `start` receives the application Context followed by the JSON request so
/// Android system trust can be initialized without hidden ActivityThread APIs.
/// The other five operations retain `(String) -> String`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_start(
    env: EnvUnowned<'_>,
    _class: JClass<'_>,
    context: JObject<'_>,
    request: JString<'_>,
) -> jstring {
    with_jni(env, |env| {
        if init_platform_verifier(env, context).is_err() {
            return response_string(env, jni_error_json("start"));
        }
        dispatch_from_jni(env, "start", request)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_beginDeviceLogin(
    env: EnvUnowned<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    with_jni(env, |env| {
        dispatch_from_jni(env, "beginDeviceLogin", request)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_pollLogin(
    env: EnvUnowned<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    with_jni(env, |env| dispatch_from_jni(env, "pollLogin", request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_readRateLimits(
    env: EnvUnowned<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    with_jni(env, |env| dispatch_from_jni(env, "readRateLimits", request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_logout(
    env: EnvUnowned<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    with_jni(env, |env| dispatch_from_jni(env, "logout", request))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yunhyok_usagering_data_NativeCodexBridgeNative_shutdown(
    env: EnvUnowned<'_>,
    _class: JClass<'_>,
    request: JString<'_>,
) -> jstring {
    with_jni(env, |env| dispatch_from_jni(env, "shutdown", request))
}
