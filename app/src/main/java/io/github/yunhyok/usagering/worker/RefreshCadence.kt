package io.github.yunhyok.usagering.worker

import io.github.yunhyok.usagering.domain.UsageSnapshot

private val adaptiveMinutes = listOf(1, 3, 5, 10)

internal fun validAdaptiveMinutes(minutes: Int?): Int = minutes?.takeIf { it in adaptiveMinutes } ?: 10

internal fun nextAdaptiveMinutes(current: Int, previous: UsageSnapshot?, snapshot: UsageSnapshot?): Int {
    val index = adaptiveMinutes.indexOf(validAdaptiveMinutes(current))
    // Failed reads never speed up polling. A first successful observation starts at the maximum.
    if (snapshot != null && !snapshot.error && previous == null) return 10
    val changed = snapshot != null && !snapshot.error && previous != null &&
        listOf(previous.fiveHour?.usedPercent to snapshot.fiveHour?.usedPercent,
            previous.sevenDay?.usedPercent to snapshot.sevenDay?.usedPercent).any { (before, after) ->
            before != null && after != null && before.isFinite() && after.isFinite() && before != after
        }
    return adaptiveMinutes[(index + if (changed) -1 else 1).coerceIn(adaptiveMinutes.indices)]
}
