package io.github.yunhyok.usagering

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageWindowData
import io.github.yunhyok.usagering.widget.UsageRingWidgetReceiver
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRebootRecoveryDeviceTest {
    @Test
    fun rebootRecoveryPreservesNativeAppState() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val loginState = targetContext.getSharedPreferences("codex_login_state", Context.MODE_PRIVATE)
        assertTrue(
            "target login state must be authenticated",
            loginState.getString("status", null) == "authenticated",
        )

        val snapshot = runBlocking {
            AppGraph.usageRepository(targetContext).refresh()
        }
        assertTrue("reboot refresh must return a non-error snapshot", !snapshot.error)
        assertHasWindowWithUsedPercent(snapshot)
        assertWindowValues(snapshot.fiveHour)
        assertWindowValues(snapshot.sevenDay)

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

        val manager = AppWidgetManager.getInstance(targetContext)
        val receiver = ComponentName(targetContext, UsageRingWidgetReceiver::class.java)
        assertTrue(
            "at least one usage ring widget must remain bound",
            manager.getAppWidgetIds(receiver).isNotEmpty(),
        )
    }

    private fun assertHasWindowWithUsedPercent(snapshot: UsageSnapshot) {
        assertTrue(
            "at least one usage window must be present",
            snapshot.fiveHour != null || snapshot.sevenDay != null,
        )
        assertTrue(
            "at least one usage window must include used percent",
            snapshot.fiveHour?.usedPercent != null || snapshot.sevenDay?.usedPercent != null,
        )
    }

    private fun assertWindowValues(window: UsageWindowData?) {
        if (window == null) return
        window.usedPercent?.let {
            assertTrue("usage percent must be finite and within range", it.isFinite() && it in 0.0..100.0)
        }
        window.windowMinutes?.let {
            assertTrue("usage window duration must be positive", it > 0L)
        }
        window.resetAtEpochMillis?.let {
            assertTrue("usage reset timestamp must be positive", it > 0L)
        }
    }
}
