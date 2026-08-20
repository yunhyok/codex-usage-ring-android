package io.github.yunhyok.usagering.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.notification.UsageNotificationPublisher
import io.github.yunhyok.usagering.widget.UsageRingWidget
import kotlinx.coroutines.withTimeout

class UsageRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        withTimeout(45_000L) {
            val snapshot = AppGraph.usageRepository(applicationContext).refresh()
            UsageRingWidget().updateAll(applicationContext)
            if (UsageWorkScheduler.notificationsEnabled(applicationContext)) {
                UsageNotificationPublisher(applicationContext).publish(snapshot)
            }
        }
        Result.success()
    }.getOrElse { Result.retry() }
}
