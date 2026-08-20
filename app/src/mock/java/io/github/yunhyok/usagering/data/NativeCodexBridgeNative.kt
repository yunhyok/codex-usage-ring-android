package io.github.yunhyok.usagering.data

/** Mock flavor fail-closed stub: it never loads JNI or exposes a network-backed login. */
internal object NativeCodexBridgeNative {
    fun start(request: String): String = "{\"ok\":false,\"error\":\"native_unavailable\"}"
    fun beginDeviceLogin(request: String): String = "{\"ok\":false,\"error\":\"native_unavailable\"}"
    fun pollLogin(request: String): String = "{\"ok\":false,\"error\":\"native_unavailable\"}"
    fun readRateLimits(request: String): String = "{\"ok\":false,\"error\":\"native_unavailable\"}"
    fun logout(request: String): String = "{\"ok\":false,\"error\":\"native_unavailable\"}"
    fun shutdown(request: String): String = "{\"ok\":false,\"error\":\"native_unavailable\"}"
}
