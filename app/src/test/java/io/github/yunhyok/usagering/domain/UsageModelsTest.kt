package io.github.yunhyok.usagering.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageModelsTest {
    @Test fun unknownIsNotZero() {
        val selected = selectUsage(UsageSnapshot(fiveHour = UsageWindowData(), capturedAtEpochMillis = 0), 0)
        assertNull(selected.remainingPercent)
        assertEquals(UsageQuality.UNKNOWN, selectUsage(null, 0).quality)
    }

    @Test fun remainingIsFlooredAndClamped() {
        assertEquals(67, remainingPercent(32.9))
        assertEquals(0, remainingPercent(150.0))
        assertEquals(100, remainingPercent(-3.0))
        assertNull(remainingPercent(Double.NaN))
        assertNull(remainingPercent(Double.POSITIVE_INFINITY))
    }

    @Test fun choosesLowerRemainingAndFiveHourOnTie() {
        val tie = UsageSnapshot(UsageWindowData(20.0), UsageWindowData(20.0), 100)
        assertEquals(UsageWindow.FIVE_HOUR, selectUsage(tie, 100).window)
        val lowerSeven = tie.copy(sevenDay = UsageWindowData(80.0))
        assertEquals(UsageWindow.SEVEN_DAY, selectUsage(lowerSeven, 100).window)
    }

    @Test fun sparseMergePreservesOmittedFields() {
        val previous = UsageSnapshot(UsageWindowData(20.0, 123), UsageWindowData(40.0, 456), 10)
        val merged = mergeSparse(previous, UsageSnapshotPatch(fiveHour = UsageWindowData(25.0)), 20)
        assertEquals(25.0, merged.fiveHour?.usedPercent)
        assertEquals(123L, merged.fiveHour?.resetAtEpochMillis)
        assertEquals(40.0, merged.sevenDay?.usedPercent)
    }

    @Test fun sparseMergePreservesAndExplicitlyClearsErrorState() {
        val previous = UsageSnapshot(capturedAtEpochMillis = 10, error = true)
        assertEquals(true, mergeSparse(previous, UsageSnapshotPatch(), 20).error)
        assertEquals(false, mergeSparse(previous, UsageSnapshotPatch(error = false), 20).error)
    }

    @Test fun staleRemainsVisibleUntilItsWindowResets() {
        val snapshot = UsageSnapshot(UsageWindowData(20.0), capturedAtEpochMillis = 0)
        assertEquals(UsageQuality.LIVE, selectUsage(snapshot, 60 * 60 * 1000L).quality)
        assertEquals(UsageQuality.STALE, selectUsage(snapshot, 60 * 60 * 1000L + 1).quality)
        assertEquals(UsageQuality.STALE, selectUsage(snapshot, 24 * 60 * 60 * 1000L).quality)
    }

    @Test fun resetExpiryMakesWindowUnknownWithoutClearingSparseData() {
        val snapshot = UsageSnapshot(
            fiveHour = UsageWindowData(20.0, resetAtEpochMillis = 100),
            sevenDay = UsageWindowData(40.0, resetAtEpochMillis = 10_000),
            capturedAtEpochMillis = 100,
        )
        val selected = selectUsage(snapshot, 200)
        assertEquals(UsageWindow.SEVEN_DAY, selected.window)
        assertEquals(60, selected.remainingPercent)
        assertEquals(20.0, snapshot.fiveHour?.usedPercent)
        val allExpired = selectUsage(snapshot, 20_000)
        assertEquals(UsageQuality.UNKNOWN, allExpired.quality)
        assertNull(allExpired.remainingPercent)
    }

    @Test fun errorStateIsDistinctFromUnavailable() {
        val error = selectUsage(UsageSnapshot(capturedAtEpochMillis = 1, error = true), 1)
        val unavailable = selectUsage(UsageSnapshot(capturedAtEpochMillis = 1), 1)
        assertEquals(UsageQuality.ERROR, error.quality)
        assertEquals(UsageQuality.UNKNOWN, unavailable.quality)
    }
}
