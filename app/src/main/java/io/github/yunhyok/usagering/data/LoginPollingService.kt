package io.github.yunhyok.usagering.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.yunhyok.usagering.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** User-started, dataSync-only foreground polling while the visible login flow is active. */
class LoginPollingService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var pollJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (pollJob?.isActive == true) return START_NOT_STICKY
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        pollJob?.cancel()
        pollJob = scope.launch {
            val controller = io.github.yunhyok.usagering.app.AppGraph.loginController(applicationContext)
            while (isActive) {
                if (shouldContinueLoginPolling(controller.poll())) {
                    delay(POLL_INTERVAL)
                } else {
                    stopSelf()
                    // A terminal native state must not fall through into a
                    // second poll iteration. The service is stopped here,
                    // and the loop exits immediately even if stopSelf is
                    // deferred by the framework.
                    break
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.login), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableVibration(false)
        })
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_neutral)
            .setContentTitle(getString(R.string.login))
            .setContentText(getString(R.string.login_waiting))
            .setSilent(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.yunhyok.usagering.action.START_LOGIN_POLL"
        const val NOTIFICATION_ID = 5031
        const val CHANNEL_ID = "login_polling"
        const val POLL_INTERVAL = 5_000L
    }
}

internal fun shouldContinueLoginPolling(state: LoginState): Boolean =
    state is LoginState.WaitingForApproval
