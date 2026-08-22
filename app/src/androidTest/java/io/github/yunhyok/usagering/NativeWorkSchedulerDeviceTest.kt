package io.github.yunhyok.usagering

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.yunhyok.usagering.worker.UsageWorkScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeWorkSchedulerDeviceTest {
    @Test
    fun bootRestoreHasOneActiveRefreshWork() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val bootRestoreEnabled = runBlocking {
            UsageWorkScheduler.bootRestoreEnabled(targetContext)
        }
        assertTrue("boot restore must be enabled", bootRestoreEnabled)

        val workInfos = WorkManager.getInstance(targetContext)
            .getWorkInfosForUniqueWork("codex_usage_ring_refresh")
            .get(10, TimeUnit.SECONDS)
        val activeWork = workInfos.filter { !it.state.isFinished }
        assertTrue("exactly one active refresh work must exist", activeWork.size == 1)

        val state = activeWork.single().state
        assertTrue(
            "active refresh work must be enqueued, running, or blocked",
            state == WorkInfo.State.ENQUEUED ||
                state == WorkInfo.State.RUNNING ||
                state == WorkInfo.State.BLOCKED,
        )
    }
}
