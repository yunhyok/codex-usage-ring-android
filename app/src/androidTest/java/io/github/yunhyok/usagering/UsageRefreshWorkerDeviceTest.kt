package io.github.yunhyok.usagering

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.data.MockUsageRepository.Scenario
import io.github.yunhyok.usagering.worker.UsageRefreshWorker
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
        val manager = WorkManager.getInstance(context)
        try {
            for (scenario in listOf(Scenario.ERROR, Scenario.FIFTY)) {
                repository.scenario = scenario
                val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
                try {
                    manager.enqueue(request).result.get(10, TimeUnit.SECONDS)
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
                    }
                } finally {
                    manager.cancelWorkById(request.id).result.get(10, TimeUnit.SECONDS)
                }
            }
        } finally {
            repository.scenario = previousScenario
            repository.refresh()
        }
    }
}
