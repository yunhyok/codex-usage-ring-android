package io.github.yunhyok.usagering

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.app.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual physical-device evidence only. Before running, allow the managed
 * ChatGPT token to reach its normal proactive-refresh condition; this test
 * performs an ordinary usage refresh and never edits auth state or forces a
 * refresh RPC. Ordinary connected CI skips unless the explicit argument gate
 * is enabled.
 */
@RunWith(AndroidJUnit4::class)
class NativeAuthRefreshEvidenceDeviceTest {
    @Test
    fun ordinaryRateLimitReadCanRecordNewNaturalRefreshObservation() {
        val args = InstrumentationRegistry.getArguments()
        val enabled = args.getString("usageRingNaturalRefreshEvidence")
            ?.equals("true", ignoreCase = true) == true
        assumeTrue("natural refresh evidence argument is disabled", enabled)
        val baseline = args.getString("baselineObservationCount")?.toLongOrNull() ?: -1L
        val notBefore = args.getString("notBeforeEpochMillis")?.toLongOrNull() ?: -1L
        assertTrue("baseline observation count argument is required and nonnegative", baseline >= 0L)
        assertTrue("not-before epoch argument is required and positive", notBefore > 0L)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = AppGraph.nativeBridge(context)
        val before = bridge.authRefreshEvidence()
        assertTrue("stored observation count is older than the supplied baseline", before.observationCount >= baseline)
        runBlocking { AppGraph.usageRepository(context).refresh() }
        val after = bridge.authRefreshEvidence()
        assertTrue("natural auth-refresh observation was not recorded", after.observationCount > baseline)
        assertTrue("natural observation time is before the supplied boundary", after.lastObservedAtEpochMillis >= notBefore)
    }
}
