package io.github.yunhyok.usagering

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageWindowData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeRateLimitsDeviceTest {
    @Test
    fun refreshesAuthenticatedNativeRateLimits() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val loginState = targetContext.getSharedPreferences("codex_login_state", Context.MODE_PRIVATE)
        val status = loginState.getString("status", null)
        assertTrue("target login state must be authenticated", status == "authenticated")

        val snapshots = runBlocking {
            val repository = AppGraph.usageRepository(targetContext)
            buildList(capacity = 25) {
                repeat(25) {
                    add(repository.refresh())
                }
            }
        }
        snapshots.forEach(::assertSnapshot)
    }

    private fun assertSnapshot(snapshot: UsageSnapshot) {
        assertTrue("refresh must return a non-error snapshot", !snapshot.error)
        assertTrue(
            "at least one usage window must be present",
            snapshot.fiveHour != null || snapshot.sevenDay != null,
        )
        assertTrue(
            "at least one usage window must include used percent",
            snapshot.fiveHour?.usedPercent != null || snapshot.sevenDay?.usedPercent != null,
        )

        assertWindow(snapshot.fiveHour)
        assertWindow(snapshot.sevenDay)
    }

    private fun assertWindow(window: UsageWindowData?) {
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
