package io.github.yunhyok.usagering

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageWindowData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeConnectivityDeviceTest {
    @Test
    fun offlineRefreshPreservesStoredSnapshot() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertAuthenticated(targetContext)

        val repository = AppGraph.usageRepository(targetContext)
        val stored = runBlocking { repository.read() }
        assertNotNull("stored usage snapshot must be available", stored)
        val previous = stored!!
        assertHasWindowWithUsedPercent(previous)

        val refreshed = runBlocking { repository.refresh() }
        assertTrue("offline refresh must return an error snapshot", refreshed.error)
        assertTrue("five-hour cached window must be preserved", refreshed.fiveHour == previous.fiveHour)
        assertTrue("seven-day cached window must be preserved", refreshed.sevenDay == previous.sevenDay)
        assertTrue(
            "cached capture timestamp must be preserved",
            refreshed.capturedAtEpochMillis == previous.capturedAtEpochMillis,
        )
    }

    @Test
    fun onlineRefreshRecovers() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertAuthenticated(targetContext)

        val snapshot = runBlocking {
            AppGraph.usageRepository(targetContext).refresh()
        }
        assertTrue("online refresh must return a non-error snapshot", !snapshot.error)
        assertHasWindowWithUsedPercent(snapshot)
        assertWindowValues(snapshot.fiveHour)
        assertWindowValues(snapshot.sevenDay)
    }

    /** One ordinary read; only fixed native categories may appear in failure output. */
    @Test
    fun ordinaryNativeReadReportsSanitizedFailure() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("usageRingNativeDiagnostic") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = AppGraph.nativeBridge(context)
        assertTrue("native diagnostic start must succeed", bridge.start().ok)
        val result = bridge.readRateLimits()
        val allowed = setOf(
            "RATE_LIMITS_AUTH_REQUIRED", "RATE_LIMITS_BACKEND", "RATE_LIMITS_SERVER",
            "RATE_LIMITS_TRANSPORT", "RATE_LIMITS_TIMEOUT", "RATE_LIMITS_DESERIALIZE",
            "RATE_LIMITS_UNAVAILABLE", "RATE_LIMITS_HTTP_401", "RATE_LIMITS_HTTP_403",
            "RATE_LIMITS_HTTP_429", "RATE_LIMITS_HTTP_4XX", "RATE_LIMITS_HTTP_5XX", "RATE_LIMITS_EMPTY",
            "NOT_READY", "RUNTIME_UNAVAILABLE", "LOGIN_IN_PROGRESS",
        )
        val category = result.exceptionOrNull()?.message?.takeIf { it in allowed }
            ?: "UNCLASSIFIED_NATIVE_ERROR"
        assertTrue("native rate-limit category: $category", result.isSuccess)
    }
    private fun assertAuthenticated(context: Context) {
        val loginState = context.getSharedPreferences("codex_login_state", Context.MODE_PRIVATE)
        assertTrue(
            "target login state must be authenticated",
            loginState.getString("status", null) == "authenticated",
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
