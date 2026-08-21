package io.github.yunhyok.usagering.data

import io.github.yunhyok.usagering.domain.UsageSnapshotPatch
import io.github.yunhyok.usagering.domain.UsageWindowData

/** Converts only named Rust DTO fields into sparse usage windows. */
class NativeUsageSource(private val bridge: NativeCodexBridge) : UsageSource {
    override suspend fun fetch(): UsageSnapshotPatch {
        // start() is idempotent and must be checked on every refresh. Login
        // cancellation/logout shuts the in-process client down, so caching a
        // Kotlin-only started flag would permanently strand later refreshes.
        val result = bridge.start()
        if (!result.ok) {
            if (result.errorCode in setOf("NOT_READY", "NATIVE_UNAVAILABLE", "INVALID_REQUEST")) return UsageSnapshotPatch(error = false)
            error(result.errorCode ?: "NATIVE_START_FAILED")
        }
        val limits = bridge.readRateLimits().getOrElse { error(it.message ?: "NATIVE_RATE_LIMITS_FAILED") }
        val five = if (limits.fiveHourUsedPercent != null || limits.fiveHourResetAtEpochMillis != null || limits.fiveHourWindowMinutes != null) {
            UsageWindowData(limits.fiveHourUsedPercent, limits.fiveHourResetAtEpochMillis, limits.fiveHourWindowMinutes)
        } else null
        val seven = if (limits.sevenDayUsedPercent != null || limits.sevenDayResetAtEpochMillis != null || limits.sevenDayWindowMinutes != null) {
            UsageWindowData(limits.sevenDayUsedPercent, limits.sevenDayResetAtEpochMillis, limits.sevenDayWindowMinutes)
        } else null
        return UsageSnapshotPatch(
            fiveHour = five,
            sevenDay = seven,
            capturedAtEpochMillis = if (five != null || seven != null) System.currentTimeMillis() else null,
            error = false,
        )
    }
}
