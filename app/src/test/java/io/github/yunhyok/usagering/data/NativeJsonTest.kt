package io.github.yunhyok.usagering.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeJsonTest {
    @Test fun decodesOnlyAllowlistedChallengeFields() {
        val result = NativeJson.challenge(
            """{"ok":true,"method":"beginDeviceLogin","result":{"verification_url":"https://example.test/device","user_code":"ABCD-EFGH","token":"never-read"}}""",
        ).getOrThrow()
        assertEquals("https://example.test/device", result.verificationUri)
        assertEquals("ABCD-EFGH", result.userCode)
    }

    @Test fun rejectsNonHttpsChallengeUrl() {
        assertEquals(false, NativeJson.challenge("""{"ok":true,"method":"beginDeviceLogin","result":{"verification_uri":"http://bad","user_code":"X"}}""").isSuccess)
    }

    @Test fun sparseRateLimitsKeepMissingValuesUnknown() {
        val limits = NativeJson.limits("""{"ok":true,"method":"readRateLimits","result":{"status":"unavailable"}}""").getOrThrow()
        assertNull(limits.fiveHourUsedPercent)
        assertNull(limits.sevenDayUsedPercent)
    }

    @Test fun decodesExplicitWindowNamesWithoutGuessingPrimarySecondary() {
        val limits = NativeJson.limits(
            """{"ok":true,"method":"readRateLimits","result":{"five_hour_used_percent":32.5,"five_hour_reset_at_epoch_millis":1700000000000,"five_hour_window_minutes":300,"seven_day_used_percent":71.0,"seven_day_window_minutes":10080}}""",
        ).getOrThrow()
        assertEquals(32.5, limits.fiveHourUsedPercent)
        assertEquals(1700000000000L, limits.fiveHourResetAtEpochMillis)
        assertEquals(10080L, limits.sevenDayWindowMinutes)
    }

    @Test fun pollNeverReturnsCredentials() {
        val result = NativeJson.poll("""{"ok":true,"method":"pollLogin","result":{"status":"authenticated","access_token":"secret"}}""")
        assertEquals(LoginPollResult.Authenticated, result)
    }

    @Test fun rejectsUndocumentedPollAliases() {
        val result = NativeJson.poll("""{"ok":true,"method":"pollLogin","result":{"status":"completed"}}""")
        assertEquals(LoginPollResult.Failed("INVALID_RESPONSE"), result)
    }
}
