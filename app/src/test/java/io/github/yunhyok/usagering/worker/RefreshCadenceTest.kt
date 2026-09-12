package io.github.yunhyok.usagering.worker

import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageWindowData
import io.github.yunhyok.usagering.worker.UsageWorkScheduler.RefreshInterval
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshCadenceTest {
    private fun snapshot(used: Double = 10.0, seven: Double = 20.0, captured: Long = 1L) = UsageSnapshot(
        fiveHour = UsageWindowData(usedPercent = used),
        sevenDay = UsageWindowData(usedPercent = seven),
        capturedAtEpochMillis = captured,
    )

    @Test fun activitySpeedsUpAndInactivitySlowsDownWithoutCrossingBounds() {
        var minutes = 10
        for (expected in listOf(5, 3, 1, 1)) {
            minutes = nextAdaptiveMinutes(minutes, snapshot(), snapshot(used = 11.0))
            assertEquals(expected, minutes)
        }
        for (expected in listOf(3, 5, 10, 10)) {
            minutes = nextAdaptiveMinutes(minutes, snapshot(), snapshot(captured = 999L))
            assertEquals(expected, minutes)
        }
    }

    @Test fun eitherWindowAndResetDecreasesCountAsUsageChanges() {
        assertEquals(5, nextAdaptiveMinutes(10, snapshot(), snapshot(seven = 21.0)))
        assertEquals(5, nextAdaptiveMinutes(10, snapshot(), snapshot(used = 0.0)))
        assertEquals(5, nextAdaptiveMinutes(10, snapshot(), snapshot(used = 10.1)))
        val metadataOnly = snapshot().copy(fiveHour = UsageWindowData(10.0, resetAtEpochMillis = 123L))
        assertEquals(10, nextAdaptiveMinutes(5, snapshot(), metadataOnly))
    }

    @Test fun unknownAndFailedReadsNeverAcceleratePolling() {
        assertEquals(10, nextAdaptiveMinutes(1, null, snapshot()))
        assertEquals(5, nextAdaptiveMinutes(3, snapshot(), null))
        assertEquals(5, nextAdaptiveMinutes(3, snapshot(), snapshot(used = 50.0).copy(error = true)))
        assertEquals(10, nextAdaptiveMinutes(5, snapshot(), snapshot(used = Double.NaN)))
        assertEquals(10, nextAdaptiveMinutes(5, snapshot(), snapshot().copy(fiveHour = null)))
        assertEquals(10, nextAdaptiveMinutes(60, snapshot(), snapshot()))
    }

    @Test fun defaultAndLegacySettingsMigrateWithoutReintroducingSixtyMinutes() {
        assertEquals(RefreshInterval.ADAPTIVE, RefreshInterval.fromStored(null))
        assertEquals(RefreshInterval.ADAPTIVE, RefreshInterval.fromStored(-1))
        assertEquals(RefreshInterval.THIRTY, RefreshInterval.fromStored(60))
        RefreshInterval.entries.forEach { mode -> assertEquals(mode, RefreshInterval.fromStored(mode.storedValue)) }
        assertEquals(listOf(3, 5, 10, 15, 30), RefreshInterval.entries.filter { it != RefreshInterval.ADAPTIVE }.map { it.minutes })
    }
}
