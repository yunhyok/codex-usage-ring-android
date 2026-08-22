package io.github.yunhyok.usagering.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
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
        assertEquals(PersistedLoginState("failed", errorCode = "PROCESS_RESTARTED"), store.value)
        assertFalse(store.value.toString().contains("https://"))
        assertFalse(store.value.toString().contains("CODE-123"))
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

        assertEquals(LoginState.Failed("NATIVE_ERROR"), controller.state)
        assertTrue(controller.start() is LoginState.WaitingForApproval)
        assertEquals(1, bridge.shutdowns)
        assertEquals(1, bridge.beginDeviceLoginCalls)
    }

    @Test fun nativeLoginCodesUseExactAllowlistAndRawDetailsUseFallback() {
        val nativeCodes = listOf(
            "LOGIN_IN_PROGRESS",
            "LOGIN_FAILED",
            "LOGIN_START_TRANSPORT",
            "LOGIN_START_TLS",
            "LOGIN_START_TLS_REVOKED",
            "LOGIN_START_DNS",
            "LOGIN_START_NOT_SUPPORTED",
            "LOGIN_START_SERVER",
            "LOGIN_START_HTTP_4XX",
            "LOGIN_START_HTTP_5XX",
            "LOGIN_START_RATE_LIMITED",
            "LOGIN_START_DESERIALIZE",
            "LOGIN_START_TIMEOUT",
            "LOGIN_TIMEOUT",
        )
        nativeCodes.forEach { code ->
            val store = MemoryStore()
            val bridge = FakeBridge().apply { pollResult = LoginPollResult.Failed(code) }
            val controller = DeviceCodeLoginController(store, bridge) { 1L }
            controller.start()
            assertEquals(LoginState.Failed(code), controller.poll())
            assertEquals(code, store.value?.errorCode)
        }

        val raw = "https://accounts.example.test/callback?access_token=secret"
        val store = MemoryStore()
        val bridge = FakeBridge().apply { pollResult = LoginPollResult.Failed(raw) }
        val controller = DeviceCodeLoginController(store, bridge) { 1L }
        controller.start()
        assertEquals(LoginState.Failed("NATIVE_ERROR"), controller.poll())
        assertEquals("NATIVE_ERROR", store.value?.errorCode)
        assertFalse(store.value.toString().contains(raw))

        val restoredStore = MemoryStore().apply { value = PersistedLoginState("failed", errorCode = raw) }
        val restored = DeviceCodeLoginController(restoredStore, FakeBridge()) { 1L }
        assertEquals(LoginState.Failed("NATIVE_ERROR"), restored.state)
        assertEquals("NATIVE_ERROR", restoredStore.value?.errorCode)
        assertFalse(restoredStore.value.toString().contains(raw))
    }

    @Test fun failedLogoutNeverReportsSignedOutOrStopsRuntime() {
        val store = MemoryStore().apply { value = PersistedLoginState("authenticated") }
        val bridge = FakeBridge().apply { logoutSucceeds = false }
        val controller = DeviceCodeLoginController(store, bridge) { 1L }

        assertEquals(LoginState.Failed("LOGOUT_FAILED"), controller.logout())
        assertEquals(0, bridge.shutdowns)
        assertEquals(1, bridge.logouts)
    }

    @Test fun failAbortsChallengeAndPersistsOnlyAllowlistedLocalCode() {
        val store = MemoryStore()
        val bridge = FakeBridge()
        val controller = DeviceCodeLoginController(store, bridge) { 1L }

        controller.start()
        assertEquals(
            LoginState.Failed("POLLING_SERVICE_UNAVAILABLE"),
            controller.fail("POLLING_SERVICE_UNAVAILABLE"),
        )
        assertEquals(1, bridge.shutdowns)
        assertEquals("POLLING_SERVICE_UNAVAILABLE", store.value?.errorCode)
    }

    @Test fun operationCoordinatorOwnsAtomicCleanupOutsideCompositionScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val bridge = FakeBridge()
        val controller = DeviceCodeLoginController(MemoryStore(), bridge) { 1L }
        var serviceStarts = 0
        var serviceStops = 0
        val operations = LoginOperationCoordinator(
            loginController = controller,
            startPolling = { serviceStarts++; true },
            stopPolling = { serviceStops++ },
            scope = workerScope,
        )

        val startJob = operations.start()
        assertEquals(LoginAction.START, operations.actionFlow.value)
        advanceUntilIdle()
        startJob?.join()
        assertEquals(1, serviceStarts)
        assertEquals(0, serviceStops)
        assertTrue(controller.state is LoginState.WaitingForApproval)
        assertEquals(null, operations.actionFlow.value)

        val cancelJob = operations.cancel()
        assertEquals(LoginAction.CANCEL, operations.actionFlow.value)
        advanceUntilIdle()
        cancelJob?.join()
        assertEquals(LoginState.SignedOut, controller.state)
        assertEquals(1, serviceStops)
        assertEquals(null, operations.actionFlow.value)
        operations.close()
    }

    @Test fun operationCoordinatorAbortsChallengeWhenPollingServiceCannotStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val bridge = FakeBridge()
        val controller = DeviceCodeLoginController(MemoryStore(), bridge) { 1L }
        var serviceStops = 0
        val operations = LoginOperationCoordinator(
            loginController = controller,
            startPolling = { false },
            stopPolling = { serviceStops++ },
            scope = workerScope,
        )

        val job = operations.start()
        advanceUntilIdle()
        job?.join()
        assertEquals(LoginState.Failed("POLLING_SERVICE_UNAVAILABLE"), controller.state)
        assertEquals(1, serviceStops)
        operations.close()
    }

    private class MemoryStore : LoginStateStore {
        var value: PersistedLoginState? = null
        override fun load() = value
        override fun save(value: PersistedLoginState) { this.value = value }
    }

    private class FakeBridge : NativeCodexBridge {
        var shutdowns = 0
        var beginDeviceLoginCalls = 0
        var logouts = 0
        var logoutSucceeds = true
        var pollResult: LoginPollResult = LoginPollResult.Waiting
        override fun start() = NativeCallResult(true)
        override fun beginDeviceLogin(): Result<DeviceCodeChallenge> {
            beginDeviceLoginCalls++
            return Result.success(DeviceCodeChallenge("https://example.test/device", "CODE-123"))
        }
        override fun pollLogin() = pollResult
        override fun readRateLimits() = Result.success(NativeRateLimits())
        override fun logout(): NativeCallResult { logouts++; return NativeCallResult(logoutSucceeds, errorCode = if (logoutSucceeds) null else "LOGOUT_FAILED") }
        override fun shutdown(): NativeCallResult { shutdowns++; return NativeCallResult(true) }
    }
}
