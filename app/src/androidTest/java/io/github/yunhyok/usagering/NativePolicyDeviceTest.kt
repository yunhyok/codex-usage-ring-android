package io.github.yunhyok.usagering

import android.content.Context
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.data.NativeCodexBridgeNative
import java.lang.reflect.Modifier
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePolicyDeviceTest {
    @Test
    fun runtimeNetworkSecurityPolicyAllowsOnlyTheCrlDistributionHost() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(
            "c.pki.goog must retain the narrowly scoped CRL cleartext exception",
            policy.isCleartextTrafficPermitted("c.pki.goog"),
        )
        assertFalse(
            "an unrelated host must remain cleartext-denied",
            policy.isCleartextTrafficPermitted("example.com"),
        )
    }

    @Test
    fun nativeStartReportsRestrictedPolicy() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val request = startRequest(targetContext)
        val rawResponse = NativeCodexBridgeNative.start(targetContext, request)
        val parsedResponse = runCatching { JSONObject(rawResponse) }.getOrNull()
        assertNotNull("native start response must be valid JSON", parsedResponse)
        val response = parsedResponse!!

        assertTrue("native start call must succeed", response.optBoolean("ok", false))
        assertTrue("native start method must be start", response.optString("method") == "start")
        val result = response.optJSONObject("result")
        assertNotNull("native start result must exist", result)
        val startResult = result!!
        assertTrue(
            "native start status must be ready",
            startResult.has("status") && startResult.optString("status") == "ready",
        )
        val metadata = startResult.optJSONObject("metadata")
        assertNotNull("native start metadata must exist", metadata)
        val policy = metadata!!
        assertTrue(
            "native implementation marker must be in-process",
            policy.optString("implementation") == "codex-in-process",
        )
        assertFalsePolicy(policy, "telemetry", "native telemetry must be disabled")
        assertFalsePolicy(policy, "plugins", "native plugins must be disabled")
        assertFalsePolicy(policy, "mcp", "native MCP must be disabled")
        assertFalsePolicy(policy, "shell", "native shell must be disabled")
        assertFalsePolicy(
            policy,
            "auth_refresh_supported",
            "explicit forced auth refresh must not be exposed through JNI",
        )
    }

    @Test
    fun nativeMethodSurfaceMatchesAllowlist() {
        val declaredNativeMethods = NativeCodexBridgeNative::class.java.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .map { it.name }
            .toSet()
        val expectedNativeMethods = setOf(
            "start",
            "beginDeviceLogin",
            "pollLogin",
            "readRateLimits",
            "logout",
            "shutdown",
        )
        assertTrue(
            "native method surface must match the allowlist",
            declaredNativeMethods == expectedNativeMethods,
        )
    }

    private fun startRequest(context: Context): String {
        val filesDirectory = context.filesDir.resolve("codex").canonicalPath
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "{\"filesDir\":\"$filesDirectory\",\"schemaVersion\":1}"
    }

    private fun assertFalsePolicy(metadata: JSONObject, name: String, message: String) {
        assertTrue(message, metadata.has(name) && !metadata.optBoolean(name, true))
    }
}
