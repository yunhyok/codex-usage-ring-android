package io.github.yunhyok.usagering.data

/** Boundary for an eventual signed/native Codex login integration. This build has no HTTP/token code. */
interface NativeCodexBridge {
    fun deviceCodeLogin(): DeviceCodeLoginState
}

sealed interface DeviceCodeLoginState {
    data object Unavailable : DeviceCodeLoginState
    data class AwaitingUser(val verificationUri: String, val userCode: String) : DeviceCodeLoginState
    data object Authenticated : DeviceCodeLoginState
}

class DefaultNativeCodexBridge : NativeCodexBridge {
    override fun deviceCodeLogin(): DeviceCodeLoginState {
        if (io.github.yunhyok.usagering.BuildConfig.FLAVOR != "native") return DeviceCodeLoginState.Unavailable
        return runCatching {
            // The native gate exchanges JSON only. Until a runtime-ready response is
            // explicitly decoded, this build remains unavailable and never fabricates login state.
            NativeCodexBridgeNative.start("null")
            NativeCodexBridgeNative.beginDeviceLogin("null")
            DeviceCodeLoginState.Unavailable
        }.getOrDefault(DeviceCodeLoginState.Unavailable)
    }
}
