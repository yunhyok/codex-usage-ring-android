package io.github.yunhyok.usagering.worker

import io.github.yunhyok.usagering.domain.UsageSnapshot

internal const val ADAPTIVE_MAX_MINUTES = 3
private val adaptiveMinutes = listOf(1, 2, ADAPTIVE_MAX_MINUTES)

internal fun validAdaptiveMinutes(minutes: Int?): Int = minutes?.takeIf { it in adaptiveMinutes } ?: ADAPTIVE_MAX_MINUTES

internal fun nextAdaptiveMinutes(current: Int, previous: UsageSnapshot?, snapshot: UsageSnapshot?): Int {
    val index = adaptiveMinutes.indexOf(validAdaptiveMinutes(current))
    // Failed reads back off within the current range. First observations start at the maximum.
    if (snapshot != null && !snapshot.error && previous == null) return ADAPTIVE_MAX_MINUTES
    val changed = snapshot != null && !snapshot.error && previous != null &&
        listOf(previous.fiveHour?.usedPercent to snapshot.fiveHour?.usedPercent,
            previous.sevenDay?.usedPercent to snapshot.sevenDay?.usedPercent).any { (before, after) ->
            before != null && after != null && before.isFinite() && after.isFinite() && before != after
        }
    // React on the first observed change; only inactivity backs off gradually.
    return if (changed) adaptiveMinutes.first() else adaptiveMinutes[(index + 1).coerceAtMost(adaptiveMinutes.lastIndex)]
}
