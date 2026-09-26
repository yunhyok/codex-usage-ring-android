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
        var minutes = 3
        for (expected in listOf(1, 1, 1)) {
            minutes = nextAdaptiveMinutes(minutes, snapshot(), snapshot(used = 11.0))
            assertEquals(expected, minutes)
        }
        for (expected in listOf(2, 3, 3)) {
            minutes = nextAdaptiveMinutes(minutes, snapshot(), snapshot(captured = 999L))
            assertEquals(expected, minutes)
        }
    }

    @Test fun eitherWindowAndResetDecreasesCountAsUsageChanges() {
        assertEquals(1, nextAdaptiveMinutes(3, snapshot(), snapshot(seven = 21.0)))
        assertEquals(1, nextAdaptiveMinutes(3, snapshot(), snapshot(used = 0.0)))
        assertEquals(1, nextAdaptiveMinutes(3, snapshot(), snapshot(used = 10.1)))
        val metadataOnly = snapshot().copy(fiveHour = UsageWindowData(10.0, resetAtEpochMillis = 123L))
        assertEquals(3, nextAdaptiveMinutes(2, snapshot(), metadataOnly))
    }

    @Test fun unknownAndFailedReadsNeverAcceleratePolling() {
        assertEquals(3, nextAdaptiveMinutes(1, null, snapshot()))
        assertEquals(2, nextAdaptiveMinutes(1, snapshot(), null))
        assertEquals(3, nextAdaptiveMinutes(2, snapshot(), snapshot(used = 50.0).copy(error = true)))
        assertEquals(3, nextAdaptiveMinutes(3, snapshot(), snapshot(used = Double.NaN)))
        assertEquals(3, nextAdaptiveMinutes(3, snapshot(), snapshot().copy(fiveHour = null)))
        assertEquals(3, nextAdaptiveMinutes(60, snapshot(), snapshot()))
    }

    @Test fun savedAndQueuedLegacyAdaptiveIntervalsUseTheNewBounds() {
        assertEquals(3, RefreshInterval.ADAPTIVE.minutes)
        for (old in listOf(null, 5, 10, 60, -1)) {
            assertEquals(3, validAdaptiveMinutes(old))
        }
        for (current in listOf(1, 2, 3, 5, 10)) {
            assertEquals(1, nextAdaptiveMinutes(current, snapshot(), snapshot(used = 11.0)))
        }
    }

    @Test fun defaultAndLegacySettingsMigrateWithoutReintroducingSixtyMinutes() {
        assertEquals(RefreshInterval.ADAPTIVE, RefreshInterval.fromStored(null))
        assertEquals(RefreshInterval.ADAPTIVE, RefreshInterval.fromStored(-1))
        assertEquals(RefreshInterval.THIRTY, RefreshInterval.fromStored(60))
        RefreshInterval.entries.forEach { mode -> assertEquals(mode, RefreshInterval.fromStored(mode.storedValue)) }
        assertEquals(listOf(3, 5, 10, 15, 30), RefreshInterval.entries.filter { it != RefreshInterval.ADAPTIVE }.map { it.minutes })
    }
}
