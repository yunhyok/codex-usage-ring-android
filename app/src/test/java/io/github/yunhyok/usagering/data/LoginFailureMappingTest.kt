package io.github.yunhyok.usagering.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LoginFailureMappingTest {
    @Test fun onlyAllowlistedTlsStartCodeGetsConnectionGuidance() {
        assertEquals(LoginFailureKind.TLS_CONNECTION, classifyLoginFailure("LOGIN_START_TLS_REVOKED"))
        assertEquals(LoginFailureKind.GENERIC, classifyLoginFailure("LOGIN_START_TLS"))
        assertEquals(LoginFailureKind.GENERIC, classifyLoginFailure("NATIVE_ERROR"))
    }
}
