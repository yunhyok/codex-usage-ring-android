package io.github.yunhyok.usagering.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.UsageSnapshotPatch
import io.github.yunhyok.usagering.domain.UsageWindowData
import io.github.yunhyok.usagering.domain.mergeSparse
import kotlinx.coroutines.flow.first

private val Context.usageDataStore by preferencesDataStore("usage_ring")

class StoredUsageRepository(private val context: Context, private val source: UsageSource) : UsageRepository {
    private object Keys {
        val fiveUsed = doublePreferencesKey("five_used")
        val fiveReset = longPreferencesKey("five_reset")
        val fiveWindow = longPreferencesKey("five_window_minutes")
        val sevenUsed = doublePreferencesKey("seven_used")
        val sevenReset = longPreferencesKey("seven_reset")
        val sevenWindow = longPreferencesKey("seven_window_minutes")
        val captured = longPreferencesKey("captured")
        val error = booleanPreferencesKey("error")
    }

    override suspend fun read(nowEpochMillis: Long): UsageSnapshot? {
        val values = context.usageDataStore.data.first()
        val captured = values[Keys.captured] ?: return null
        return UsageSnapshot(
            fiveHour = if (values[Keys.fiveUsed] != null || values[Keys.fiveReset] != null || values[Keys.fiveWindow] != null)
                UsageWindowData(values[Keys.fiveUsed], values[Keys.fiveReset], values[Keys.fiveWindow]) else null,
            sevenDay = if (values[Keys.sevenUsed] != null || values[Keys.sevenReset] != null || values[Keys.sevenWindow] != null)
                UsageWindowData(values[Keys.sevenUsed], values[Keys.sevenReset], values[Keys.sevenWindow]) else null,
            capturedAtEpochMillis = captured,
            error = values[Keys.error] ?: false,
        )
    }

    override suspend fun refresh(nowEpochMillis: Long): UsageSnapshot {
        val previous = read(nowEpochMillis)
        val patch = runCatching { source.fetch() }.getOrElse { UsageSnapshotPatch(error = true) }
        val merged = mergeSparse(previous, patch, nowEpochMillis)
        context.usageDataStore.edit { p ->
            merged.fiveHour?.usedPercent?.let { p[Keys.fiveUsed] = it }
            merged.fiveHour?.resetAtEpochMillis?.let { p[Keys.fiveReset] = it }
            merged.fiveHour?.windowMinutes?.let { p[Keys.fiveWindow] = it }
            merged.sevenDay?.usedPercent?.let { p[Keys.sevenUsed] = it }
            merged.sevenDay?.resetAtEpochMillis?.let { p[Keys.sevenReset] = it }
            merged.sevenDay?.windowMinutes?.let { p[Keys.sevenWindow] = it }
            p[Keys.captured] = merged.capturedAtEpochMillis
            p[Keys.error] = merged.error
        }
        return merged
    }
}
