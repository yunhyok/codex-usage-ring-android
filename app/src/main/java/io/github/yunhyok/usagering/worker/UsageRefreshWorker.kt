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
import io.github.yunhyok.usagering.domain.UsageSnapshot

class UsageRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // One quick retry; persistent failures (including signed-out accounts)
        // wait for the next normal period instead of accumulating hours of backoff.
        var previous: UsageSnapshot? = null
        var snapshot: UsageSnapshot? = null
        val succeeded = try {
            withTimeoutOrNull(45_000L) {
                val repository = AppGraph.usageRepository(applicationContext)
                previous = repository.read()
                val refreshed = repository.refresh()
                snapshot = refreshed
                UsageRingWidget().updateAll(applicationContext)
                if (UsageWorkScheduler.notificationsEnabled(applicationContext)) {
                    UsageNotificationPublisher(applicationContext).publish(refreshed)
                }
                !refreshed.error
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!succeeded && runAttemptCount == 0) return Result.retry()
        return try {
            if (inputData.getBoolean(UsageWorkScheduler.SCHEDULED, false)) {
                UsageWorkScheduler.completeScheduledRun(
                    applicationContext, id,
                    inputData.getInt(UsageWorkScheduler.INTERVAL_MINUTES, ADAPTIVE_MAX_MINUTES), previous,
                    snapshot.takeIf { succeeded },
                )
            } else {
                UsageWorkScheduler.schedule(applicationContext)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A scheduling failure must retry; otherwise the one-time chain would stop.
            Result.retry()
        }
    }
}
