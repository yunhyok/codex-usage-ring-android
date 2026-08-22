package io.github.yunhyok.usagering.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginPollingServiceTest {
    @Test
    fun terminalStatesStopBeforeAnotherPoll() {
        assertTrue(
            shouldContinueLoginPolling(
                LoginState.WaitingForApproval(
                    verificationUri = "https://example.test/device",
                    userCode = "REDACTED",
                    startedAtEpochMillis = 1L,
                    expiresAtEpochMillis = 2L,
                ),
            ),
        )
        assertFalse(shouldContinueLoginPolling(LoginState.Authenticated))
        assertFalse(shouldContinueLoginPolling(LoginState.SignedOut))
        assertFalse(shouldContinueLoginPolling(LoginState.Failed("TIMEOUT")))
    }
}
