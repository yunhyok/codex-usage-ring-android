package io.github.yunhyok.usagering.domain

import kotlin.math.floor

enum class UsageWindow { FIVE_HOUR, SEVEN_DAY }

/** A value of null means the source did not provide that field. It is never rendered as zero. */
data class UsageWindowData(
    val usedPercent: Double? = null,
    val resetAtEpochMillis: Long? = null,
)

data class UsageSnapshot(
    val fiveHour: UsageWindowData? = null,
    val sevenDay: UsageWindowData? = null,
    val capturedAtEpochMillis: Long,
    val error: Boolean = false,
)

data class UsageSnapshotPatch(
    val fiveHour: UsageWindowData? = null,
    val sevenDay: UsageWindowData? = null,
    val capturedAtEpochMillis: Long? = null,
    val error: Boolean? = null,
)

enum class UsageQuality { LIVE, STALE, UNKNOWN, ERROR }

data class SelectedUsage(
    val window: UsageWindow? = null,
    val remainingPercent: Int? = null,
    val resetAtEpochMillis: Long? = null,
    val quality: UsageQuality = UsageQuality.UNKNOWN,
)

private const val STALE_AFTER_MILLIS = 60 * 60 * 1000L

fun mergeSparse(previous: UsageSnapshot?, patch: UsageSnapshotPatch, nowEpochMillis: Long): UsageSnapshot {
    val priorFive = previous?.fiveHour
    val priorSeven = previous?.sevenDay
    fun merge(old: UsageWindowData?, incoming: UsageWindowData?) = when {
        incoming == null -> old
        old == null -> incoming
        else -> UsageWindowData(
            usedPercent = incoming.usedPercent ?: old.usedPercent,
            resetAtEpochMillis = incoming.resetAtEpochMillis ?: old.resetAtEpochMillis,
        )
    }
    return UsageSnapshot(
        fiveHour = merge(priorFive, patch.fiveHour),
        sevenDay = merge(priorSeven, patch.sevenDay),
        capturedAtEpochMillis = patch.capturedAtEpochMillis ?: previous?.capturedAtEpochMillis ?: nowEpochMillis,
        error = patch.error ?: previous?.error ?: false,
    )
}

fun remainingPercent(usedPercent: Double?): Int? = usedPercent?.takeIf { it.isFinite() }?.let {
    floor((100.0 - it).coerceIn(0.0, 100.0)).toInt()
}

/** Select the lower remaining window. A tie intentionally chooses five_hour. */
fun selectUsage(snapshot: UsageSnapshot?, nowEpochMillis: Long): SelectedUsage {
    if (snapshot == null) {
        return SelectedUsage(quality = UsageQuality.UNKNOWN)
    }
    if (snapshot.error) return SelectedUsage(quality = UsageQuality.ERROR)
    val age = nowEpochMillis - snapshot.capturedAtEpochMillis
    val candidates = listOf(
        UsageWindow.FIVE_HOUR to snapshot.fiveHour,
        UsageWindow.SEVEN_DAY to snapshot.sevenDay,
    ).mapNotNull { (window, data) ->
        // A reset marks the end of that usage window. Keep the sparse source data,
        // but never display an expired window as a made-up zero.
        if (data?.resetAtEpochMillis != null && data.resetAtEpochMillis <= nowEpochMillis) {
            return@mapNotNull null
        }
        val remaining = remainingPercent(data?.usedPercent) ?: return@mapNotNull null
        Triple(window, data, remaining)
    }
    val selected = candidates.minWithOrNull(compareBy<Triple<UsageWindow, UsageWindowData?, Int>> { it.third }
        .thenBy { if (it.first == UsageWindow.FIVE_HOUR) 0 else 1 })
        ?: return SelectedUsage(quality = UsageQuality.UNKNOWN)
    return SelectedUsage(
        window = selected.first,
        remainingPercent = selected.third,
        resetAtEpochMillis = selected.second?.resetAtEpochMillis,
        quality = if (age > STALE_AFTER_MILLIS) UsageQuality.STALE else UsageQuality.LIVE,
    )
}

fun statusBucket(remainingPercent: Int?): Int = when {
    remainingPercent == null -> 0
    remainingPercent <= 0 -> 0
    remainingPercent >= 100 -> 10
    else -> ((remainingPercent * 10) / 100).coerceIn(0, 10)
}
