package io.github.yunhyok.usagering.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NativeUsageSourceTest {
    @Test fun failedNativeStartIsAnErrorRatherThanSuccessfulUnknownUsage() = kotlinx.coroutines.test.runTest {
        for (code in listOf("NOT_READY", "NATIVE_UNAVAILABLE", "INVALID_REQUEST", "START_FAILED")) {
            val bridge = FakeBridge(startResult = NativeCallResult(false, errorCode = code))
            val failure = runCatching { NativeUsageSource(bridge).fetch() }.exceptionOrNull()
            assertTrue("failed native start must propagate", failure is IllegalStateException)
            assertEquals(code, failure?.message)
            assertEquals(0, bridge.reads)
        }
    }
    @Test fun blockingNativeCallsLeaveTheCallingThread() = kotlinx.coroutines.test.runTest {
        val caller = Thread.currentThread()
        val bridge = FakeBridge()
        NativeUsageSource(bridge).fetch()
        assertNotEquals(caller, bridge.startThread)
        assertNotEquals(caller, bridge.readThread)
    }

    @Test fun explicitWindowValuesMapWithoutPrimarySecondaryGuessing() = kotlinx.coroutines.test.runTest {
        val bridge = FakeBridge(limits = NativeRateLimits(
            fiveHourUsedPercent = 25.0,
            fiveHourWindowMinutes = 300,
            sevenDayUsedPercent = 70.0,
            sevenDayWindowMinutes = 10_080,
        ))
        val source = NativeUsageSource(bridge)
        val patch = source.fetch()
        assertEquals(25.0, patch.fiveHour?.usedPercent)
        assertEquals(70.0, patch.sevenDay?.usedPercent)
        assertEquals(300L, patch.fiveHour?.windowMinutes)
        assertEquals(10_080L, patch.sevenDay?.windowMinutes)
        source.fetch()
        assertEquals(2, bridge.starts)
    }

    private class FakeBridge(
        private val startResult: NativeCallResult = NativeCallResult(true),
        private val limits: NativeRateLimits = NativeRateLimits(),
    ) : NativeCodexBridge {
        var startThread: Thread? = null
        var readThread: Thread? = null
        var starts = 0
        var reads = 0
        override fun start(): NativeCallResult { startThread = Thread.currentThread(); starts++; return startResult }
        override fun beginDeviceLogin() = Result.failure<DeviceCodeChallenge>(IllegalStateException())
        override fun pollLogin() = LoginPollResult.Waiting
        override fun readRateLimits(): Result<NativeRateLimits> { readThread = Thread.currentThread(); reads++; return Result.success(limits) }
        override fun logout() = NativeCallResult(true)
        override fun shutdown() = NativeCallResult(true)
    }
}
