package io.github.yunhyok.usagering.worker

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.core.app.NotificationManagerCompat
import io.github.yunhyok.usagering.notification.UsageNotificationPublisher
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

object UsageWorkScheduler {
    private const val UNIQUE_NAME = "codex_usage_ring_refresh"
    private const val IMMEDIATE_NAME = "codex_usage_ring_refresh_now"

    enum class RefreshInterval(val minutes: Int) { FIFTEEN(15), THIRTY(30), SIXTY(60) }

    private val Context.schedulerDataStore by preferencesDataStore("usage_ring_scheduler")
    private val intervalKey = intPreferencesKey("interval_minutes")
    private val notificationsKey = booleanPreferencesKey("notifications_enabled")
    private val bootRestoreKey = booleanPreferencesKey("boot_restore_enabled")

    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    suspend fun savedInterval(context: Context): RefreshInterval = when (context.schedulerDataStore.data.first()[intervalKey]) {
        15 -> RefreshInterval.FIFTEEN
        60 -> RefreshInterval.SIXTY
        else -> RefreshInterval.THIRTY
    }

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

    suspend fun setInterval(context: Context, interval: RefreshInterval) {
        context.schedulerDataStore.edit {
            it[intervalKey] = interval.minutes
            it[bootRestoreKey] = true
        }
        schedule(context, interval)
    }

    fun schedule(context: Context, interval: RefreshInterval = RefreshInterval.THIRTY) {
        val request = PeriodicWorkRequestBuilder<UsageRefreshWorker>(interval.minutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UsageRefreshWorker>()
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }
}
