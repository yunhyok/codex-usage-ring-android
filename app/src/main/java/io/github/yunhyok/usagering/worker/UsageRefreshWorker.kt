package io.github.yunhyok.usagering.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.notification.UsageNotificationPublisher
import io.github.yunhyok.usagering.widget.UsageRingWidget
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

class UsageRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // One quick retry; persistent failures (including signed-out accounts)
        // wait for the next normal period instead of accumulating hours of backoff.
        val failureResult = if (runAttemptCount == 0) Result.retry() else Result.success()
        return try {
            withTimeoutOrNull(45_000L) {
                val snapshot = AppGraph.usageRepository(applicationContext).refresh()
                UsageRingWidget().updateAll(applicationContext)
                if (UsageWorkScheduler.notificationsEnabled(applicationContext)) {
                    UsageNotificationPublisher(applicationContext).publish(snapshot)
                }
                if (snapshot.error) failureResult else Result.success()
            } ?: failureResult
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failureResult
        }
    }
}
