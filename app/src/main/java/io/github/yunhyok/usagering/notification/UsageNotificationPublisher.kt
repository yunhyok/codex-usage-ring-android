package io.github.yunhyok.usagering.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.widget.RemoteViews
import io.github.yunhyok.usagering.R
import io.github.yunhyok.usagering.MainActivity
import io.github.yunhyok.usagering.domain.UsageSnapshot
import io.github.yunhyok.usagering.domain.selectUsage
import io.github.yunhyok.usagering.domain.statusBucket

class UsageNotificationPublisher(private val context: Context) {
    fun publish(snapshot: UsageSnapshot) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val selected = selectUsage(snapshot, System.currentTimeMillis())
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        })
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val windowLabel = when (selected.window) {
            io.github.yunhyok.usagering.domain.UsageWindow.FIVE_HOUR -> context.getString(R.string.five_hour_window)
            io.github.yunhyok.usagering.domain.UsageWindow.SEVEN_DAY -> context.getString(R.string.seven_day_window)
            null -> context.getString(R.string.unknown)
        }
        val exactValue = selected.remainingPercent?.let {
            context.getString(R.string.notification_exact_remaining, it, windowLabel)
        } ?: context.getString(R.string.notification_exact_unknown, windowLabel)
        val custom = RemoteViews(context.packageName, R.layout.notification_usage).apply {
            setTextViewText(R.id.notification_title, context.getString(R.string.notification_title))
            setTextViewText(R.id.notification_value, exactValue)
            selected.remainingPercent?.let { remaining ->
                setViewVisibility(R.id.notification_remaining, View.VISIBLE)
                setProgressBar(R.id.notification_remaining, 100, remaining, false)
            } ?: setViewVisibility(R.id.notification_remaining, View.GONE)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconForBucket(statusBucket(selected.remainingPercent)))
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(selected.remainingPercent?.let { context.getString(R.string.notification_remaining, it) } ?: context.getString(R.string.unknown))
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setCustomContentView(custom)
            .setCustomBigContentView(custom)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setSilent(true)
            .setVibrate(longArrayOf(0L))
            .setSound(null)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    companion object {
        const val CHANNEL_ID = "usage_status"
        const val NOTIFICATION_ID = 5030
        fun iconForBucket(bucket: Int): Int = when (bucket.coerceIn(0, 10)) {
            0 -> R.drawable.status_bucket_0
            1 -> R.drawable.status_bucket_1
            2 -> R.drawable.status_bucket_2
            3 -> R.drawable.status_bucket_3
            4 -> R.drawable.status_bucket_4
            5 -> R.drawable.status_bucket_5
            6 -> R.drawable.status_bucket_6
            7 -> R.drawable.status_bucket_7
            8 -> R.drawable.status_bucket_8
            9 -> R.drawable.status_bucket_9
            else -> R.drawable.status_bucket_10
        }
        fun bucketFor(snapshot: UsageSnapshot): Int = statusBucket(selectUsage(snapshot, System.currentTimeMillis()).remainingPercent)
    }
}
