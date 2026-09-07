package io.github.yunhyok.usagering

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.widget.UsageRingWidgetReceiver
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeWidgetDeviceTest {
    @Test
    fun boundUsageRingWidgetsExposeLauncherResizeOptions() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AppWidgetManager.getInstance(targetContext)
        val receiver = ComponentName(targetContext, UsageRingWidgetReceiver::class.java)
        val boundWidgetIds = manager.getAppWidgetIds(receiver)
        assertTrue("at least one usage ring widget must be bound", boundWidgetIds.isNotEmpty())

        boundWidgetIds.forEach { widgetId ->
            assertWidgetOptions(manager.getAppWidgetOptions(widgetId))
        }

        val providerInfo = manager.getAppWidgetInfo(boundWidgetIds.first())
        assertNotNull("bound usage ring provider metadata must be available", providerInfo)
        val metadata = providerInfo!!
        assertTrue(
            "usage ring provider must allow horizontal resize",
            metadata.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
        )
        assertTrue(
            "usage ring provider must allow vertical resize",
            metadata.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
        )
        assertTrue(
            "usage ring provider must support the home screen",
            metadata.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0,
        )
    }

    private fun assertWidgetOptions(options: android.os.Bundle) {
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)

        assertTrue("widget minimum width must be positive", minWidth > 0)
        assertTrue("widget maximum width must be positive", maxWidth > 0)
        assertTrue("widget minimum height must be positive", minHeight > 0)
        assertTrue("widget maximum height must be positive", maxHeight > 0)
        assertTrue("widget horizontal bounds must be ordered", minWidth <= maxWidth)
        assertTrue("widget vertical bounds must be ordered", minHeight <= maxHeight)
    }
}
