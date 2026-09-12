package io.github.yunhyok.usagering

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.data.MockUsageRepository.Scenario
import io.github.yunhyok.usagering.worker.UsageRefreshWorker
import io.github.yunhyok.usagering.worker.UsageWorkScheduler
import io.github.yunhyok.usagering.worker.UsageWorkScheduler.RefreshInterval
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Mutating scheduler scenarios are restricted to an isolated mock installation. */
@RunWith(AndroidJUnit4::class)
class RefreshCadenceDeviceTest {
    @Test fun settingsRetirePeriodicWorkAndRepeatedRestorePreservesTheDueTime() = runBlocking {
        assumeTrue(BuildConfig.FLAVOR == "mock")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val legacy = PeriodicWorkRequestBuilder<UsageRefreshWorker>(60, TimeUnit.MINUTES)
            .setInitialDelay(60, TimeUnit.MINUTES).build()
        manager.enqueueUniquePeriodicWork("codex_usage_ring_refresh", ExistingPeriodicWorkPolicy.REPLACE, legacy).await()
        try {
            for (mode in RefreshInterval.entries) {
                UsageWorkScheduler.setInterval(context, mode)
                assertEquals(mode, UsageWorkScheduler.savedInterval(context))
                val pending = active(manager).single()
                assertNull(pending.periodicityInfo)
                assertEquals(mode.minutes * 60_000L, pending.initialDelayMillis)
                UsageWorkScheduler.schedule(context)
                UsageWorkScheduler.schedule(context)
                val restored = active(manager).single()
                assertEquals(pending.id, restored.id)
                assertEquals(pending.nextScheduleTimeMillis, restored.nextScheduleTimeMillis)
            }
            assertTrue(manager.getWorkInfosForUniqueWorkFlow("codex_usage_ring_refresh")
                .first().all { it.state.isFinished })
        } finally {
            UsageWorkScheduler.setInterval(context, RefreshInterval.ADAPTIVE)
        }
    }

    @Test fun completedWorkerQueuesOneAdaptiveSuccessorAndCancelledWorkCannotReplaceSettings() = runBlocking {
        assumeTrue(BuildConfig.FLAVOR == "mock")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = WorkManager.getInstance(context)
        val repository = AppGraph.mockRepository(context)!!
        val original = repository.scenario
        UsageWorkScheduler.setInterval(context, RefreshInterval.ADAPTIVE)
        try {
            // Only the initial test request skips its delay. The real worker constructs each successor.
            for ((current, expected, changed) in listOf(
                Triple(10, 5, true), Triple(5, 3, true), Triple(3, 1, true), Triple(1, 1, true),
                Triple(1, 3, false), Triple(3, 5, false), Triple(5, 10, false), Triple(10, 10, false),
            )) {
                repository.scenario = Scenario.TEN
                repository.refresh()
                if (changed) repository.scenario = Scenario.TWENTY
                val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
                    .setInputData(workDataOf(UsageWorkScheduler.SCHEDULED to true, UsageWorkScheduler.INTERVAL_MINUTES to current))
                    .build()
                manager.enqueueUniqueWork(UsageWorkScheduler.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request).await()
                val done = withTimeout(20_000) {
                    manager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }!!
                }
                assertEquals(WorkInfo.State.SUCCEEDED, done.state)
                val next = active(manager).single()
                assertEquals(expected * 60_000L, next.initialDelayMillis)
                assertEquals(WorkInfo.State.ENQUEUED, next.state)
                assertFalse(next.id == request.id)
                // A duplicate completion or a late cancelled worker cannot add a second successor.
                UsageWorkScheduler.completeScheduledRun(context, request.id, current, null, null)
                assertEquals(next.id, active(manager).single().id)
            }
            val old = active(manager).single()
            UsageWorkScheduler.setInterval(context, RefreshInterval.THREE)
            val changed = active(manager).single()
            UsageWorkScheduler.completeScheduledRun(context, old.id, 10, null, null)
            assertEquals(changed.id, active(manager).single().id)
            assertEquals(180_000L, changed.initialDelayMillis)
        } finally {
            repository.scenario = original
            repository.refresh()
            UsageWorkScheduler.setInterval(context, RefreshInterval.ADAPTIVE)
        }
    }

    private suspend fun active(manager: WorkManager): List<WorkInfo> =
        manager.getWorkInfosForUniqueWorkFlow(UsageWorkScheduler.UNIQUE_NAME).first().filter { !it.state.isFinished }
}
