package io.github.yunhyok.usagering.data

import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.mergeSparse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeUsageSourceTest {
    @Test fun unavailableRefreshDoesNotAdvanceLastGoodTimestamp() = kotlinx.coroutines.test.runTest {
        val source = NativeUsageSource(FakeBridge(startResult = NativeCallResult(false, errorCode = "NOT_READY")))
        val previous = UsageSnapshot(capturedAtEpochMillis = 100L, error = true)
        val merged = mergeSparse(previous, source.fetch(), 2_000L)
        assertEquals(100L, merged.capturedAtEpochMillis)
        assertEquals(false, merged.error)
        assertNull(merged.fiveHour)
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
        var starts = 0
        override fun start(): NativeCallResult { starts++; return startResult }
        override fun beginDeviceLogin() = Result.failure<DeviceCodeChallenge>(IllegalStateException())
        override fun pollLogin() = LoginPollResult.Waiting
        override fun readRateLimits() = Result.success(limits)
        override fun logout() = NativeCallResult(true)
        override fun shutdown() = NativeCallResult(true)
    }
}
