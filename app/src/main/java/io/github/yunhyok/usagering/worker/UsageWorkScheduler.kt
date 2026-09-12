package io.github.yunhyok.usagering.worker

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import androidx.core.app.NotificationManagerCompat
import io.github.yunhyok.usagering.notification.UsageNotificationPublisher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.yunhyok.usagering.domain.UsageSnapshot
import java.util.UUID

object UsageWorkScheduler {
    const val UNIQUE_NAME = "codex_usage_ring_refresh_chain"
    private const val LEGACY_NAME = "codex_usage_ring_refresh"
    private const val IMMEDIATE_NAME = "codex_usage_ring_refresh_now"
    internal const val SCHEDULED = "scheduled_refresh"
    internal const val INTERVAL_MINUTES = "scheduled_interval_minutes"
    private val scheduleMutex = Mutex()

    enum class RefreshInterval(val minutes: Int) {
        ADAPTIVE(10), THREE(3), FIVE(5), TEN(10), FIFTEEN(15), THIRTY(30);

        val storedValue: Int get() = if (this == ADAPTIVE) 0 else minutes

        companion object {
            fun fromStored(value: Int?): RefreshInterval = when (value) {
                3 -> THREE
                5 -> FIVE
                10 -> TEN
                15 -> FIFTEEN
                30, 60 -> THIRTY
                else -> ADAPTIVE
            }
        }
    }

    private val Context.schedulerDataStore by preferencesDataStore("usage_ring_scheduler")
    private val intervalKey = intPreferencesKey("interval_minutes")
    private val adaptiveMinutesKey = intPreferencesKey("adaptive_minutes")
    private val notificationsKey = booleanPreferencesKey("notifications_enabled")
    private val bootRestoreKey = booleanPreferencesKey("boot_restore_enabled")

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    suspend fun savedInterval(context: Context): RefreshInterval =
        RefreshInterval.fromStored(context.schedulerDataStore.data.first()[intervalKey])

    suspend fun notificationsEnabled(context: Context): Boolean =
        context.schedulerDataStore.data.first()[notificationsKey] ?: false

    suspend fun bootRestoreEnabled(context: Context): Boolean =
        context.schedulerDataStore.data.first()[bootRestoreKey] ?: false

    suspend fun setBootRestoreEnabled(context: Context, enabled: Boolean) {
        context.schedulerDataStore.edit { it[bootRestoreKey] = enabled }
    }

    suspend fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        context.schedulerDataStore.edit { it[notificationsKey] = enabled }
        if (!enabled) {
            NotificationManagerCompat.from(context).cancel(UsageNotificationPublisher.NOTIFICATION_ID)
        }
    }

    suspend fun setInterval(context: Context, interval: RefreshInterval) = scheduleMutex.withLock {
        context.schedulerDataStore.edit {
            it[intervalKey] = interval.storedValue
            it[adaptiveMinutesKey] = RefreshInterval.ADAPTIVE.minutes
            it[bootRestoreKey] = true
        }
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request(interval.minutes)).await()
        manager.cancelUniqueWork(LEGACY_NAME).await()
    }

    suspend fun schedule(context: Context) = scheduleMutex.withLock {
        val preferences = context.schedulerDataStore.data.first()
        val mode = RefreshInterval.fromStored(preferences[intervalKey])
        val minutes = if (mode == RefreshInterval.ADAPTIVE) {
            validAdaptiveMinutes(preferences[adaptiveMinutesKey])
        } else mode.minutes
        val manager = WorkManager.getInstance(context)
        // KEEP preserves the due time when the app opens or boot restoration repeats.
        manager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request(minutes)).await()
        // Queue the replacement first, including when an old periodic worker migrates itself.
        manager.cancelUniqueWork(LEGACY_NAME).await()
    }

    internal suspend fun completeScheduledRun(
        context: Context,
        workerId: UUID,
        currentMinutes: Int,
        previous: UsageSnapshot?,
        snapshot: UsageSnapshot?,
    ) = scheduleMutex.withLock {
        val manager = WorkManager.getInstance(context)
        val active = manager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first().filter { !it.state.isFinished }
        // A changed setting cancels the old chain. A retried completion must not append twice.
        if (active.none { it.id == workerId && it.state == WorkInfo.State.RUNNING } ||
            active.any { it.id != workerId }) return@withLock
        val mode = savedInterval(context)
        val nextMinutes = if (mode == RefreshInterval.ADAPTIVE) {
            nextAdaptiveMinutes(currentMinutes, previous, snapshot)
        } else mode.minutes
        context.schedulerDataStore.edit { it[adaptiveMinutesKey] = nextMinutes }
        // Await persistence before reporting success; process death cannot lose the successor.
        manager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(nextMinutes)).await()
    }

    private fun request(minutes: Int) = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
        .setInitialDelay(minutes.toLong(), TimeUnit.MINUTES)
        .setInputData(workDataOf(SCHEDULED to true, INTERVAL_MINUTES to minutes))
        .setConstraints(constraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }
}
