package io.github.yunhyok.usagering.data

/** JNI boundary only compiled by the native flavor. The Rust library owns all credentials and transport. */
internal object NativeCodexBridgeNative {
    init { System.loadLibrary("usage_ring_codex") }

    external fun start(request: String): String
    external fun beginDeviceLogin(request: String): String
    external fun pollLogin(request: String): String
    external fun readRateLimits(request: String): String
    external fun logout(request: String): String
    external fun shutdown(request: String): String
}
