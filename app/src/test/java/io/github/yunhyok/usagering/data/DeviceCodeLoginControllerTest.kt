package io.github.yunhyok.usagering.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeLoginControllerTest {
    @Test fun timeoutCancelsNativeRuntimeAndDoesNotPersistChallenge() {
        var now = 1_000L
        val store = MemoryStore()
        val bridge = FakeBridge()
        val controller = DeviceCodeLoginController(store, bridge) { now }
        assertTrue(controller.start() is LoginState.WaitingForApproval)
        assertTrue(store.value.toString().contains("waiting"))
        assertFalse(store.value.toString().contains("https://"))
        assertFalse(store.value.toString().contains("CODE"))
        now += 15 * 60 * 1000L
        assertEquals(LoginState.Failed("TIMEOUT"), controller.poll())
        assertEquals(1, bridge.shutdowns)
    }

    @Test fun restartDropsChallengeAndStateFlowHasOneOwner() {
        val store = MemoryStore()
        val first = DeviceCodeLoginController(store, FakeBridge()) { 10L }
        first.start()
        val restarted = DeviceCodeLoginController(store, FakeBridge()) { 10L }
        assertEquals(LoginState.Failed("PROCESS_RESTARTED"), restarted.state)
        assertEquals(restarted.state, restarted.stateFlow.value)
    }

    @Test fun cancelAndLogoutShutdownAndLogoutExactlyOnce() {
        val store = MemoryStore()
        val bridge = FakeBridge()
        val controller = DeviceCodeLoginController(store, bridge) { 1L }
        controller.start()
        controller.cancel()
        controller.start()
        controller.logout()
        assertEquals(2, bridge.shutdowns)
        assertEquals(1, bridge.logouts)
    }

    @Test fun retryAfterFailureResetsNativeRuntimeBeforeStartingAgain() {
        val store = MemoryStore().apply {
            value = PersistedLoginState("failed", errorCode = "TRANSIENT")
        }
        val bridge = FakeBridge()
        val controller = DeviceCodeLoginController(store, bridge) { 1L }

        assertEquals(LoginState.Failed("TRANSIENT"), controller.state)
        assertTrue(controller.start() is LoginState.WaitingForApproval)
        assertEquals(1, bridge.shutdowns)
    }

    @Test fun failedLogoutNeverReportsSignedOutOrStopsRuntime() {
        val store = MemoryStore().apply { value = PersistedLoginState("authenticated") }
        val bridge = FakeBridge().apply { logoutSucceeds = false }
        val controller = DeviceCodeLoginController(store, bridge) { 1L }

        assertEquals(LoginState.Failed("LOGOUT_FAILED"), controller.logout())
        assertEquals(0, bridge.shutdowns)
        assertEquals(1, bridge.logouts)
    }

    private class MemoryStore : LoginStateStore {
        var value: PersistedLoginState? = null
        override fun load() = value
        override fun save(value: PersistedLoginState) { this.value = value }
    }

    private class FakeBridge : NativeCodexBridge {
        var shutdowns = 0
        var logouts = 0
        var logoutSucceeds = true
        override fun start() = NativeCallResult(true)
        override fun beginDeviceLogin() = Result.success(DeviceCodeChallenge("https://example.test/device", "CODE-123"))
        override fun pollLogin() = LoginPollResult.Waiting
        override fun readRateLimits() = Result.success(NativeRateLimits())
        override fun logout(): NativeCallResult { logouts++; return NativeCallResult(logoutSucceeds, errorCode = if (logoutSucceeds) null else "LOGOUT_FAILED") }
        override fun shutdown(): NativeCallResult { shutdowns++; return NativeCallResult(true) }
    }
}
