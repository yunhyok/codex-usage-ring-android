package io.github.yunhyok.usagering

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.data.MockUsageRepository.Scenario
import io.github.yunhyok.usagering.worker.UsageRefreshWorker
import io.github.yunhyok.usagering.worker.UsageWorkScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageRefreshWorkerDeviceTest {
    @Test
    fun failedFetchIsRetriedAndSuccessfulFetchCompletes() = runBlocking {
        assumeTrue(BuildConfig.FLAVOR == "mock")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AppGraph.mockRepository(context)!!
        val previousScenario = repository.scenario
        val previousInterval = UsageWorkScheduler.savedInterval(context)
        val manager = WorkManager.getInstance(context)
        UsageWorkScheduler.setInterval(context, UsageWorkScheduler.RefreshInterval.ADAPTIVE)
        try {
            for (scenario in listOf(Scenario.ERROR, Scenario.FIFTY)) {
                repository.scenario = scenario
                val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
                    .setInputData(workDataOf(UsageWorkScheduler.SCHEDULED to true, UsageWorkScheduler.INTERVAL_MINUTES to 3))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
                try {
                    manager.enqueueUniqueWork(UsageWorkScheduler.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
                        .result.get(10, TimeUnit.SECONDS)
                    val finishedAttempt = withTimeout(20_000) {
                        var info: WorkInfo
                        do {
                            delay(100)
                            info = manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)!!
                        } while (!info.state.isFinished && !(info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0))
                        info
                    }
                    assertEquals(
                        if (scenario == Scenario.ERROR) WorkInfo.State.ENQUEUED else WorkInfo.State.SUCCEEDED,
                        finishedAttempt.state,
                    )
                    if (scenario == Scenario.ERROR) {
                        val afterRetry = withTimeout(30_000) {
                            var info: WorkInfo
                            do {
                                delay(100)
                                info = manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)!!
                            } while (!info.state.isFinished)
                            info
                        }
                        assertEquals(WorkInfo.State.SUCCEEDED, afterRetry.state)
                        assertTrue("capping retry must preserve the error snapshot", repository.read()!!.error)
                        val next = manager.getWorkInfosForUniqueWork(UsageWorkScheduler.UNIQUE_NAME)
                            .get(10, TimeUnit.SECONDS).filter { !it.state.isFinished }.single()
                        assertEquals("persistent failure must leave a slower successor", 300_000L, next.initialDelayMillis)
                        assertEquals(WorkInfo.State.ENQUEUED, next.state)
                    }
                } finally {
                    manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            repository.scenario = previousScenario
            repository.refresh()
            UsageWorkScheduler.setInterval(context, previousInterval)
        }
    }
}
