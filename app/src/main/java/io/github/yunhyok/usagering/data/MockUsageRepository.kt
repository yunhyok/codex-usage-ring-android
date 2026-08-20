package io.github.yunhyok.usagering.data

import android.content.Context
import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageSnapshotPatch
import io.github.yunhyok.usagering.domain.UsageWindowData

/** Deterministic source used by mockDebug and JVM tests; no network or credentials. */
class MockUsageRepository(context: Context) : UsageRepository {
    enum class Scenario(val label: String, val remaining: Int? = null) {
        ZERO("0%", 0), TEN("10%", 10), TWENTY("20%", 20), THIRTY("30%", 30),
        FORTY("40%", 40), FIFTY("50%", 50), SIXTY("60%", 60), SEVENTY("70%", 70),
        EIGHTY("80%", 80), NINETY("90%", 90), FULL("100%", 100),
        STALE("Stale", 55), ERROR("Error", null), UNAVAILABLE("Unavailable", null), RESET_EXPIRED("Reset expired", 25),
    }

    private val preferences = context.applicationContext.getSharedPreferences("usage_ring_mock", Context.MODE_PRIVATE)

    @Volatile var scenario: Scenario = runCatching {
        Scenario.valueOf(preferences.getString(KEY_SCENARIO, null) ?: Scenario.THIRTY.name)
    }.getOrDefault(Scenario.THIRTY)
        set(value) {
            field = value
            preferences.edit().putString(KEY_SCENARIO, value.name).apply()
        }
    private var snapshot: UsageSnapshot? = null

    override suspend fun read(nowEpochMillis: Long): UsageSnapshot? = snapshot ?: loadSnapshot()?.also { snapshot = it }

    override suspend fun refresh(nowEpochMillis: Long): UsageSnapshot {
        if (scenario == Scenario.UNAVAILABLE || scenario == Scenario.ERROR) {
            return UsageSnapshot(capturedAtEpochMillis = nowEpochMillis, error = scenario == Scenario.ERROR).also {
                snapshot = it
                persistSnapshot(it)
            }
        }
        val used = 100.0 - scenario.remaining!!
        val captured = if (scenario == Scenario.STALE) nowEpochMillis - 61 * 60 * 1000L else nowEpochMillis
        val reset = if (scenario == Scenario.RESET_EXPIRED) nowEpochMillis - 1 else nowEpochMillis + 90 * 60 * 1000L
        val result = UsageSnapshotPatch(
            fiveHour = UsageWindowData(usedPercent = used, resetAtEpochMillis = reset),
            sevenDay = UsageWindowData(used + 5.0, resetAtEpochMillis = if (scenario == Scenario.RESET_EXPIRED) reset else nowEpochMillis + 5 * 24 * 60 * 60 * 1000L),
            capturedAtEpochMillis = captured,
        )
        snapshot = if (scenario == Scenario.RESET_EXPIRED || scenario == Scenario.STALE) {
            UsageSnapshot(result.fiveHour, result.sevenDay, captured)
        } else io.github.yunhyok.usagering.domain.mergeSparse(snapshot, result, nowEpochMillis)
        return snapshot!!.also(::persistSnapshot)
    }

    private fun persistSnapshot(value: UsageSnapshot) {
        preferences.edit()
            .clear()
            .putString(KEY_SCENARIO, scenario.name)
            .putLong(KEY_CAPTURED, value.capturedAtEpochMillis)
            .putBoolean(KEY_ERROR, value.error)
            .apply {
                value.fiveHour?.usedPercent?.let { putLong(KEY_FIVE_USED, it.toRawBits()) }
                value.fiveHour?.resetAtEpochMillis?.let { putLong(KEY_FIVE_RESET, it) }
                value.sevenDay?.usedPercent?.let { putLong(KEY_SEVEN_USED, it.toRawBits()) }
                value.sevenDay?.resetAtEpochMillis?.let { putLong(KEY_SEVEN_RESET, it) }
            }
            .apply()
    }

    private fun loadSnapshot(): UsageSnapshot? {
        if (!preferences.contains(KEY_CAPTURED)) return null
        fun used(key: String): Double? = if (preferences.contains(key)) {
            Double.fromBits(preferences.getLong(key, 0L))
        } else null
        fun reset(key: String): Long? = if (preferences.contains(key)) preferences.getLong(key, 0L) else null
        val fiveUsed = used(KEY_FIVE_USED)
        val fiveReset = reset(KEY_FIVE_RESET)
        val sevenUsed = used(KEY_SEVEN_USED)
        val sevenReset = reset(KEY_SEVEN_RESET)
        return UsageSnapshot(
            fiveHour = if (fiveUsed != null || fiveReset != null) UsageWindowData(fiveUsed, fiveReset) else null,
            sevenDay = if (sevenUsed != null || sevenReset != null) UsageWindowData(sevenUsed, sevenReset) else null,
            capturedAtEpochMillis = preferences.getLong(KEY_CAPTURED, 0L),
            error = preferences.getBoolean(KEY_ERROR, false),
        )
    }

    private companion object {
        const val KEY_SCENARIO = "scenario"
        const val KEY_CAPTURED = "captured"
        const val KEY_ERROR = "error"
        const val KEY_FIVE_USED = "five_used"
        const val KEY_FIVE_RESET = "five_reset"
        const val KEY_SEVEN_USED = "seven_used"
        const val KEY_SEVEN_RESET = "seven_reset"
    }
}
