package io.github.yunhyok.usagering.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yunhyok.usagering.R
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.domain.selectUsage
import io.github.yunhyok.usagering.domain.UsageQuality
import kotlin.math.roundToInt

class UsageRingWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val selected = selectUsage(AppGraph.usageRepository(context).read(), System.currentTimeMillis())
        val remaining = selected.remainingPercent
        val accent = ContextCompat.getColor(context, R.color.usage_accent)
        provideContent {
            val available = LocalSize.current
            val showLabel = available.width >= 80.dp && available.height >= 100.dp
            val labelHeight = if (showLabel) (20 * context.resources.configuration.fontScale).dp else 0.dp
            val diameter = minOf(
                available.width - 8.dp,
                available.height - 8.dp - labelHeight,
            ).coerceIn(1.dp, 160.dp)
            val label = when (selected.quality) {
                UsageQuality.LIVE -> context.getString(R.string.widget_live)
                UsageQuality.STALE -> context.getString(R.string.widget_stale)
                UsageQuality.UNKNOWN -> context.getString(R.string.widget_unknown)
                UsageQuality.ERROR -> context.getString(R.string.error)
            }
            Column(
                GlanceModifier.fillMaxSize()
                    .background(ColorProvider(day = ComposeColor(0xD91B1A20), night = ComposeColor(0xD91B1A20)))
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    ImageProvider(ringBitmap(
                        (diameter.value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1),
                        remaining, selected.quality, accent,
                    )),
                    "${displayPercent(remaining, selected.quality)}, $label",
                    GlanceModifier.size(diameter),
                    contentScale = ContentScale.Fit,
                )
                if (showLabel) {
                    Text(
                        label,
                        style = TextStyle(
                            color = ColorProvider(day = ComposeColor.White, night = ComposeColor.White),
                            fontSize = 12.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    internal fun ringBitmap(size: Int, remaining: Int?, quality: UsageQuality, accent: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Draw in a fixed coordinate space so strokes and text scale with the launcher size.
        canvas.scale(size / 100f, size / 100f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(90, 94, 91)
        }
        val bounds = RectF(8f, 8f, 92f, 92f)
        if (quality == UsageQuality.UNKNOWN || quality == UsageQuality.ERROR) {
            for (i in 0 until 12) canvas.drawArc(bounds, i * 30f, 4f, false, paint)
        } else {
            canvas.drawArc(bounds, -90f, 360f, false, paint)
        }
        paint.color = if (quality == UsageQuality.LIVE) accent else Color.LTGRAY
        if (remaining != null) canvas.drawArc(bounds, -90f, 360f * (remaining / 100f), false, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (quality == UsageQuality.STALE) Color.LTGRAY else Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 24f
        val baseline = 50f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(displayPercent(remaining, quality), 50f, baseline, paint)
        return bitmap
    }

    private fun displayPercent(remaining: Int?, quality: UsageQuality): String = when {
        remaining == null -> "--%"
        quality == UsageQuality.STALE -> "~$remaining%"
        else -> "$remaining%"
    }
}

class UsageRingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageRingWidget()
}
