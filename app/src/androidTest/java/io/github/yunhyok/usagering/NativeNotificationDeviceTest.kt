package io.github.yunhyok.usagering

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.notification.UsageNotificationPublisher
import io.github.yunhyok.usagering.worker.UsageWorkScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeNotificationDeviceTest {
    @Test
    fun restoresNotificationStateAroundNativeRefresh() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        assertTrue("POST_NOTIFICATIONS must be granted", permissionGranted)

        val loginState = targetContext.getSharedPreferences("codex_login_state", Context.MODE_PRIVATE)
        assertTrue(
            "target login state must be authenticated",
            loginState.getString("status", null) == "authenticated",
        )

        val repository = AppGraph.usageRepository(targetContext)
        val storedSnapshot = runBlocking { repository.read() }
        assertNotNull("stored usage snapshot must be available", storedSnapshot)
        val initialSnapshot = storedSnapshot!!
        val initialNotificationsEnabled = runBlocking {
            UsageWorkScheduler.notificationsEnabled(targetContext)
        }
        val publisher = UsageNotificationPublisher(targetContext)
        val manager = targetContext.getSystemService(NotificationManager::class.java)

        try {
            runBlocking { UsageWorkScheduler.setNotificationsEnabled(targetContext, true) }
            publisher.publish(initialSnapshot)
            assertNotificationState(manager)

            manager.cancel(UsageNotificationPublisher.NOTIFICATION_ID)
            assertTrue(
                "usage notification must be absent after cancellation",
                manager.activeNotifications.none { it.id == UsageNotificationPublisher.NOTIFICATION_ID },
            )

            val refreshedSnapshot = runBlocking { repository.refresh() }
            val notificationsEnabled = runBlocking {
                UsageWorkScheduler.notificationsEnabled(targetContext)
            }
            if (notificationsEnabled) publisher.publish(refreshedSnapshot)
            assertNotificationState(manager)
        } finally {
            runBlocking {
                UsageWorkScheduler.setNotificationsEnabled(targetContext, initialNotificationsEnabled)
            }
            if (!initialNotificationsEnabled) {
                NotificationManagerCompat.from(targetContext).cancel(UsageNotificationPublisher.NOTIFICATION_ID)
            }
        }
    }

    private fun assertNotificationState(manager: NotificationManager) {
        val active = manager.activeNotifications.firstOrNull {
            it.id == UsageNotificationPublisher.NOTIFICATION_ID
        }
        assertNotNull("usage notification must be active", active)
        val notification = active!!.notification
        assertTrue(
            "usage notification must be ongoing",
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
        )

        val channel = manager.getNotificationChannel(UsageNotificationPublisher.CHANNEL_ID)
        assertNotNull("usage notification channel must exist", channel)
        val usageChannel = channel!!
        assertTrue(
            "usage notification channel must use low importance",
            usageChannel.importance == NotificationManager.IMPORTANCE_LOW,
        )
        assertTrue("usage notification channel badge must be disabled", !usageChannel.canShowBadge())
        assertTrue("usage notification channel vibration must be disabled", !usageChannel.shouldVibrate())
        assertTrue("usage notification channel vibration pattern must be absent", usageChannel.vibrationPattern == null)
        assertTrue("usage notification channel sound must be absent", usageChannel.sound == null)
    }
}
