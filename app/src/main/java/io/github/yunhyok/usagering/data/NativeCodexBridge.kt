package io.github.yunhyok.usagering.data

import android.content.Context
import org.json.JSONObject

data class DeviceCodeChallenge(val verificationUri: String, val userCode: String, val expiresAtEpochMillis: Long? = null)

data class NativeRateLimits(
    val fiveHourUsedPercent: Double? = null,
    val fiveHourResetAtEpochMillis: Long? = null,
    val fiveHourWindowMinutes: Long? = null,
    val sevenDayUsedPercent: Double? = null,
    val sevenDayResetAtEpochMillis: Long? = null,
    val sevenDayWindowMinutes: Long? = null,
    val authRefreshObserved: Boolean = false,
)

/** Local, non-secret evidence that a managed auth refresh was observed. */
data class AuthRefreshEvidence(val observationCount: Long = 0L, val lastObservedAtEpochMillis: Long = 0L)

internal fun recordAuthRefreshObservation(
    previous: AuthRefreshEvidence,
    observed: Boolean,
    observedAtEpochMillis: Long,
): AuthRefreshEvidence = if (!observed) {
    previous
} else {
    AuthRefreshEvidence(previous.observationCount.saturatingIncrement(), observedAtEpochMillis.coerceAtLeast(0L))
}

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1L

data class NativeCallResult(val ok: Boolean, val status: String? = null, val errorCode: String? = null)

interface NativeCodexBridge {
    fun start(): NativeCallResult
    fun beginDeviceLogin(): Result<DeviceCodeChallenge>
    fun pollLogin(): LoginPollResult
    fun readRateLimits(): Result<NativeRateLimits>
    fun logout(): NativeCallResult
    fun shutdown(): NativeCallResult
    /** Small instrumentation-facing read API; implementations expose no auth material. */
    fun authRefreshEvidence(): AuthRefreshEvidence = AuthRefreshEvidence()
    fun deviceCodeLogin(): DeviceCodeLoginState = beginDeviceLogin().fold(
        { DeviceCodeLoginState.AwaitingUser(it.verificationUri, it.userCode) },
        { DeviceCodeLoginState.Unavailable },
    )
}

sealed interface LoginPollResult {
    data object Waiting : LoginPollResult
    data object Authenticated : LoginPollResult
    data class Failed(val code: String) : LoginPollResult
}

sealed interface DeviceCodeLoginState {
    data object Unavailable : DeviceCodeLoginState
    data class AwaitingUser(val verificationUri: String, val userCode: String) : DeviceCodeLoginState
    data object Authenticated : DeviceCodeLoginState
}

/** Strict envelope/result decoder. Only Rust's named, sanitized DTO fields are read. */
internal object NativeJson {
    private fun envelope(raw: String?, method: String): Pair<JSONObject, JSONObject?>? = runCatching {
        val root = JSONObject(raw ?: error("INVALID_JSON"))
        require(root.optString("method") == method)
        root to root.optJSONObject("result")
    }.getOrNull()

    fun call(raw: String?, expectedMethod: String): NativeCallResult {
        val rootAndResult = envelope(raw, expectedMethod) ?: return NativeCallResult(false, errorCode = "INVALID_JSON")
        val root = rootAndResult.first
        val result = rootAndResult.second
        return NativeCallResult(
            ok = root.optBoolean("ok", false),
            status = result?.optString("status")?.takeUnless { it.isNullOrBlank() },
            errorCode = root.optJSONObject("error")?.optString("code")?.takeUnless { it.isNullOrBlank() },
        )
    }

    fun challenge(raw: String?): Result<DeviceCodeChallenge> = runCatching {
        val (root, result) = envelope(raw, "beginDeviceLogin") ?: error("INVALID_JSON")
        if (!root.optBoolean("ok", false)) {
            error(sanitizeLoginErrorCode(root.optJSONObject("error")?.optString("code") ?: "NATIVE_ERROR"))
        }
        val dto = result ?: error("INVALID_RESPONSE")
        val uri = dto.optString("verification_url")
        val code = dto.optString("user_code")
        require(uri.startsWith("https://")) { "INVALID_URI" }
        require(code.isNotBlank() && code.length <= 128) { "INVALID_CODE" }
        DeviceCodeChallenge(uri, code, dto.optionalLong("expires_at_epoch_millis"))
    }

    fun poll(raw: String?): LoginPollResult {
        val pair = envelope(raw, "pollLogin") ?: return LoginPollResult.Failed("INVALID_JSON")
        if (!pair.first.optBoolean("ok", false)) {
            return LoginPollResult.Failed(
                sanitizeLoginErrorCode(pair.first.optJSONObject("error")?.optString("code") ?: "NATIVE_ERROR"),
            )
        }
        return when (pair.second?.optString("status")?.lowercase()) {
            "authenticated" -> LoginPollResult.Authenticated
            "waiting" -> LoginPollResult.Waiting
            else -> LoginPollResult.Failed("INVALID_RESPONSE")
        }
    }

    fun limits(raw: String?): Result<NativeRateLimits> = runCatching {
        val (root, result) = envelope(raw, "readRateLimits") ?: error("INVALID_JSON")
        if (!root.optBoolean("ok", false)) error(root.optJSONObject("error")?.optString("code") ?: "NATIVE_ERROR")
        val dto = result ?: error("INVALID_RESPONSE")
        NativeRateLimits(
            fiveHourUsedPercent = dto.optionalDouble("five_hour_used_percent")?.validPercent(),
            fiveHourResetAtEpochMillis = dto.optionalLong("five_hour_reset_at_epoch_millis"),
            fiveHourWindowMinutes = dto.optionalLong("five_hour_window_minutes"),
            sevenDayUsedPercent = dto.optionalDouble("seven_day_used_percent")?.validPercent(),
            sevenDayResetAtEpochMillis = dto.optionalLong("seven_day_reset_at_epoch_millis"),
            sevenDayWindowMinutes = dto.optionalLong("seven_day_window_minutes"),
            authRefreshObserved = dto.optionalBoolean("auth_refresh_observed"),
        )
    }

    private fun JSONObject.optionalLong(name: String): Long? = if (has(name) && !isNull(name)) optLong(name).takeIf { it > 0L } else null
    private fun JSONObject.optionalDouble(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeIf { it.isFinite() } else null
    private fun JSONObject.optionalBoolean(name: String): Boolean = opt(name) as? Boolean ?: false
    private fun Double.validPercent(): Double? = takeIf { it in 0.0..100.0 }
}

class DefaultNativeCodexBridge(private val context: Context) : NativeCodexBridge {
    private val evidencePreferences = context.getSharedPreferences("codex_auth_refresh_evidence", Context.MODE_PRIVATE)

    private fun startRequest(): String {
        val path = context.filesDir.resolve("codex").canonicalPath.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"filesDir\":\"$path\",\"schemaVersion\":1}"
    }
    private fun emptyRequest(): String = "{}"
    override fun start(): NativeCallResult = runCatching { NativeJson.call(NativeCodexBridgeNative.start(context, startRequest()), "start") }.getOrDefault(NativeCallResult(false, errorCode = "NATIVE_UNAVAILABLE"))
    override fun beginDeviceLogin(): Result<DeviceCodeChallenge> = runCatching {
        val started = start()
        if (!started.ok && started.errorCode != "NOT_READY") error(started.errorCode ?: "NATIVE_ERROR")
        NativeJson.challenge(NativeCodexBridgeNative.beginDeviceLogin(emptyRequest())).getOrThrow().also {
            // A new device-login challenge may replace the current session;
            // never let evidence from the prior account satisfy its gate.
            clearAuthRefreshEvidence()
        }
    }
    override fun pollLogin(): LoginPollResult = runCatching { NativeJson.poll(NativeCodexBridgeNative.pollLogin(emptyRequest())) }.getOrDefault(LoginPollResult.Failed("NATIVE_UNAVAILABLE"))
    override fun readRateLimits(): Result<NativeRateLimits> = runCatching {
        NativeJson.limits(NativeCodexBridgeNative.readRateLimits(emptyRequest())).getOrThrow().also {
            if (it.authRefreshObserved) recordAuthRefreshObservation()
        }
    }
    override fun logout(): NativeCallResult = runCatching {
        val started = start()
        if (!started.ok) return@runCatching started
        NativeJson.call(NativeCodexBridgeNative.logout(emptyRequest()), "logout").also {
            if (it.ok) clearAuthRefreshEvidence()
        }
    }.getOrDefault(NativeCallResult(false, errorCode = "NATIVE_UNAVAILABLE"))
    override fun shutdown(): NativeCallResult = runCatching { NativeJson.call(NativeCodexBridgeNative.shutdown(emptyRequest()), "shutdown") }.getOrDefault(NativeCallResult(false, errorCode = "NATIVE_UNAVAILABLE"))

    override fun authRefreshEvidence(): AuthRefreshEvidence = AuthRefreshEvidence(
        observationCount = evidencePreferences.getLong(KEY_COUNT, 0L).coerceAtLeast(0L),
        lastObservedAtEpochMillis = evidencePreferences.getLong(KEY_LAST_OBSERVED_AT, 0L).coerceAtLeast(0L),
    )

    private fun recordAuthRefreshObservation() {
        val current = authRefreshEvidence()
        val next = recordAuthRefreshObservation(current, true, System.currentTimeMillis())
        evidencePreferences.edit()
            .putLong(KEY_COUNT, next.observationCount)
            .putLong(KEY_LAST_OBSERVED_AT, next.lastObservedAtEpochMillis)
            // Commit the tiny marker synchronously so a WorkManager process
            // stop cannot lose evidence that was just observed.
            .commit()
    }

    private fun clearAuthRefreshEvidence() {
        evidencePreferences.edit().clear().commit()
    }

    private companion object {
        const val KEY_COUNT = "observation_count"
        const val KEY_LAST_OBSERVED_AT = "last_observed_at_epoch_millis"
    }
}
