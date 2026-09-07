package io.github.yunhyok.usagering

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.data.StoredUsageRepository
import io.github.yunhyok.usagering.data.UsageSource
import io.github.yunhyok.usagering.domain.UsageSnapshotPatch
import io.github.yunhyok.usagering.domain.UsageWindowData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoredUsageRepositoryDeviceTest {
    @Test
    fun overlappingSparseRefreshesPreserveOrderAndFailuresPreserveTheTimestamp() = runBlocking {
        assumeTrue(BuildConfig.FLAVOR == "mock")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var calls = 0
        val repository = StoredUsageRepository(context, object : UsageSource {
            override suspend fun fetch(): UsageSnapshotPatch = when (++calls) {
                1 -> UsageSnapshotPatch(fiveHour = UsageWindowData(usedPercent = 1.0), capturedAtEpochMillis = 100, error = false)
                2 -> {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    UsageSnapshotPatch(fiveHour = UsageWindowData(usedPercent = 10.0), capturedAtEpochMillis = 200, error = false)
                }
                else -> {
                    secondEntered.complete(Unit)
                    UsageSnapshotPatch(fiveHour = UsageWindowData(resetAtEpochMillis = 300), capturedAtEpochMillis = 300, error = false)
                }
            }
        })
        repository.refresh()
        val first = async { repository.refresh() }
        withTimeout(5_000) { firstEntered.await() }
        val second = async { repository.refresh() }
        try {
            assertNull("second fetch must wait for the first transaction", withTimeoutOrNull(300) { secondEntered.await() })
        } finally {
            releaseFirst.complete(Unit)
        }
        withTimeout(5_000) { first.await(); second.await() }
        val final = repository.read()!!
        assertEquals(10.0, final.fiveHour?.usedPercent)
        assertEquals(300L, final.fiveHour?.resetAtEpochMillis)
        val failing = StoredUsageRepository(context, object : UsageSource {
            override suspend fun fetch(): UsageSnapshotPatch = error("RATE_LIMITS_UNAVAILABLE")
        })
        assertTrue(failing.refresh().error)
        assertEquals(300L, failing.read()?.capturedAtEpochMillis)
        val cancelled = StoredUsageRepository(context, object : UsageSource {
            override suspend fun fetch(): UsageSnapshotPatch = throw CancellationException()
        })
        assertTrue(runCatching { cancelled.refresh() }.exceptionOrNull() is CancellationException)
    }
}
