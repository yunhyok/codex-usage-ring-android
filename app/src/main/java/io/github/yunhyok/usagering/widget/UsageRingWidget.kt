package io.github.yunhyok.usagering.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalSize
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.yunhyok.usagering.R
import io.github.yunhyok.usagering.app.AppGraph
import io.github.yunhyok.usagering.domain.selectUsage
import io.github.yunhyok.usagering.domain.UsageQuality

class UsageRingWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(180.dp, 100.dp), DpSize(280.dp, 180.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val selected = selectUsage(AppGraph.usageRepository(context).read(), System.currentTimeMillis())
        val remaining = selected.remainingPercent
        provideContent {
            val compact = LocalSize.current.height < 140.dp
            val bitmapSize = if (compact) 64 else 96
            val label = when (selected.quality) {
                UsageQuality.LIVE -> context.getString(R.string.widget_live)
                UsageQuality.STALE -> context.getString(R.string.widget_stale)
                UsageQuality.UNKNOWN -> context.getString(R.string.widget_unknown)
                UsageQuality.ERROR -> context.getString(R.string.error)
            }
            Row(
                GlanceModifier
                    .fillMaxSize()
                    .background(
                        ColorProvider(
                            day = ComposeColor(0xB31B1A20),
                            night = ComposeColor(0xD91B1A20),
                        ),
                    )
                    .padding(if (compact) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        ImageProvider(ringBitmap(bitmapSize, remaining, selected.quality)),
                        label,
                        GlanceModifier.size(if (compact) 56.dp else 84.dp),
                        contentScale = ContentScale.Fit,
                    )
                    if (!compact) {
                        Row(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                displayPercent(context, remaining, selected.quality),
                                style = TextStyle(
                                    color = ColorProvider(day = ComposeColor.White, night = ComposeColor.White),
                                ),
                            )
                            Spacer(GlanceModifier.size(8.dp))
                            Text(
                                label,
                                style = TextStyle(
                                    color = ColorProvider(
                                        day = ComposeColor(0xFFD0CDD5),
                                        night = ComposeColor(0xFFD0CDD5),
                                    ),
                                ),
                            )
                        }
                    }
                }
                if (compact) {
                    Spacer(GlanceModifier.size(6.dp))
                    Image(
                        ImageProvider(percentBitmap(remaining, selected.quality)),
                        displayPercent(context, remaining, selected.quality),
                        GlanceModifier.size(28.dp, 56.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }

    private fun ringBitmap(size: Int, remaining: Int?, quality: UsageQuality): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND }
        val bounds = RectF(8f, 8f, size - 8f, size - 8f)
        paint.color = Color.rgb(80, 77, 86)
        if (quality == UsageQuality.UNKNOWN || quality == UsageQuality.ERROR) {
            for (i in 0 until 12) {
                val angle = Math.toRadians(i * 30.0)
                canvas.drawCircle(
                    size / 2f + (size / 2f - 8f) * kotlin.math.cos(angle).toFloat(),
                    size / 2f + (size / 2f - 8f) * kotlin.math.sin(angle).toFloat(),
                    2.5f,
                    paint,
                )
            }
        } else canvas.drawArc(bounds, -90f, 360f, false, paint)
        paint.color = when (quality) {
            UsageQuality.LIVE -> Color.rgb(103, 80, 164)
            UsageQuality.STALE -> Color.rgb(145, 145, 145)
            UsageQuality.UNKNOWN, UsageQuality.ERROR -> Color.rgb(130, 130, 130)
        }
        if (remaining != null) canvas.drawArc(bounds, -90f, 360f * (remaining / 100f), false, paint)
        drawNeutralCenter(canvas, size, quality)
        return bitmap
    }

    private fun drawNeutralCenter(canvas: Canvas, size: Int, quality: UsageQuality) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(37, 35, 42)
        canvas.drawCircle(size / 2f, size / 2f, size * .22f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = if (quality == UsageQuality.UNKNOWN) Color.rgb(190, 190, 190) else Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size * .16f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(size / 2f, size / 2f, size * .055f, paint)
    }

    private fun percentBitmap(remaining: Int?, quality: UsageQuality): Bitmap {
        val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.rotate(90f, 16f, 32f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (quality == UsageQuality.STALE) Color.LTGRAY else Color.WHITE
            textSize = 12f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            when {
                remaining == null -> "--%"
                quality == UsageQuality.STALE -> "~$remaining%"
                else -> "$remaining%"
            }, 16f, 36f, paint,
        )
        return bitmap
    }

    private fun displayPercent(context: Context, remaining: Int?, quality: UsageQuality): String = when {
        remaining == null -> "--%"
        quality == UsageQuality.STALE -> "~$remaining%"
        else -> context.getString(R.string.remaining_percent, remaining)
    }
}

class UsageRingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageRingWidget()
}
