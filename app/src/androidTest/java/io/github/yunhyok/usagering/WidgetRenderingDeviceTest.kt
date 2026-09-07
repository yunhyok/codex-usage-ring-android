package io.github.yunhyok.usagering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.data.MockUsageRepository.Scenario
import io.github.yunhyok.usagering.domain.UsageQuality
import io.github.yunhyok.usagering.domain.selectUsage
import io.github.yunhyok.usagering.widget.UsageRingWidget
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetRenderingDeviceTest {
    @Test
    fun compactAndResizedWidgetsKeepRingAndPercentVisible() = runBlocking {
        // This test changes only mock data; it must never touch a signed-in native app.
        assumeTrue(BuildConfig.FLAVOR == "mock")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = AppGraph.mockRepository(context)!!
        val previousScenario = repository.scenario
        val widget = UsageRingWidget()
        assertEquals(SizeMode.Exact, widget.sizeMode)
        val accent = ContextCompat.getColor(context, R.color.usage_accent)
        assertTrue("indicator must be bright green", Color.green(accent) >= 220 && Color.green(accent) > Color.red(accent))
        val sizes = listOf(40 to 40, 56 to 72, 180 to 40, 40 to 180, 100 to 100, 280 to 180)
        try {
            for (scenario in listOf(Scenario.ZERO, Scenario.FIFTY, Scenario.FULL, Scenario.STALE, Scenario.ERROR, Scenario.UNAVAILABLE)) {
                repository.scenario = scenario
                val selected = selectUsage(repository.refresh(), System.currentTimeMillis())
                val expected = selected.remainingPercent?.let {
                    if (selected.quality == UsageQuality.STALE) "~$it%" else "$it%"
                } ?: "--%"
                for ((widthDp, heightDp) in sizes) {
                    val views = widget.compose(context, size = DpSize(widthDp.dp, heightDp.dp))
                    instrumentation.runOnMainSync {
                        val root = views.apply(context, FrameLayout(context))
                        val density = context.resources.displayMetrics.density
                        val width = (widthDp * density).roundToInt()
                        val height = (heightDp * density).roundToInt()
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                        )
                        root.layout(0, 0, width, height)
                        val image = descendants(root).filterIsInstance<ImageView>().single()
                        assertTrue("percentage must remain accessible", image.contentDescription.startsWith(expected))
                        val bounds = android.graphics.Rect(0, 0, image.width, image.height)
                        (root as ViewGroup).offsetDescendantRectToMyCoords(image, bounds)
                        assertTrue("ring must fit the host", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
                        assertTrue("ring must be visible", image.width > 0 && image.height > 0 && image.drawable != null)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        val pixels = IntArray(width * height)
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                        val hasGreen = pixels.any { Color.green(it) >= 220 && Color.green(it) > Color.red(it) + 30 }
                        assertEquals("only live remaining usage uses green", selected.quality == UsageQuality.LIVE && selected.remainingPercent!! > 0, hasGreen)
                        File(context.getExternalFilesDir(null), "widget-${scenario.name}-${widthDp}x${heightDp}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            repository.scenario = previousScenario
            repository.refresh()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
