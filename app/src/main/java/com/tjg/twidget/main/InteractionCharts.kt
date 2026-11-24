package com.tjg.twidget.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.ui.TwidgetFonts
import java.text.NumberFormat

internal data class DonutSegment(val index: Int, val start: Float, val sweep: Float)

/** Gap angles are carved out of each slice, preserving its midpoint. */
internal fun donutSegments(counts: List<Long>, gapDegrees: Float = 3f): List<DonutSegment> {
    val total = counts.sumOf { it.coerceAtLeast(0).toDouble() }
    if (total <= 0.0) return emptyList()
    val positive = counts.count { it > 0 }
    var start = -90.0
    return counts.mapIndexedNotNull { index, value ->
        if (value <= 0) return@mapIndexedNotNull null
        val angle = 360.0 * value / total
        val gap = if (positive == 1) 0.0 else minOf(gapDegrees.coerceAtLeast(0f).toDouble(), angle * 0.4)
        DonutSegment(index, (start + gap / 2).toFloat(), (angle - gap).toFloat()).also { start += angle }
    }
}

internal fun originalFraction(originals: Int, replies: Int): Float? {
    if (originals < 0 || replies < 0 || originals.toLong() + replies == 0L) return null
    return (originals.toDouble() / (originals.toLong() + replies)).toFloat()
}

internal class InteractionDonutView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.oneui_text_primary); textAlign = Paint.Align.CENTER; typeface = TwidgetFonts.forApp(context, 700) }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.oneui_text_secondary); textAlign = Paint.Align.CENTER; typeface = TwidgetFonts.forApp(context, 400) }
    private val bounds = RectF()
    private var values = emptyList<Long>()
    private var total: Long? = null
    private val colors = listOf(R.color.interaction_likes_color, R.color.interaction_reposts_color, R.color.interaction_replies_color, R.color.interaction_quotes_color)
    fun setData(counts: List<Long>, total: Long?) {
        values = counts
        this.total = total
        val amount = total?.let { NumberFormat.getIntegerInstance(AppLocales.applicationLocale()).format(it) } ?: context.getString(R.string.interaction_missing)
        contentDescription = context.getString(R.string.interaction_value_description, context.getString(R.string.interaction_total), amount)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val stroke = minOf(18f * density, size / 7f)
        val radius = (size - stroke - 4f * density).coerceAtLeast(0f) / 2f
        val cx = width / 2f; val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.strokeWidth = stroke
        paint.color = context.getColor(R.color.oneui_divider)
        canvas.drawArc(bounds, 0f, 360f, false, paint)
        donutSegments(values).forEach { segment ->
            paint.color = context.getColor(colors[segment.index])
            canvas.drawArc(bounds, segment.start, segment.sweep, false, paint)
        }
        label.textSize = 22f * resources.displayMetrics.scaledDensity
        caption.textSize = 11f * resources.displayMetrics.scaledDensity
        val amount = total?.let { NumberFormat.getIntegerInstance(AppLocales.applicationLocale()).format(it) } ?: context.getString(R.string.interaction_missing)
        val available = radius * 1.55f
        if (label.measureText(amount) > available && available > 0f) label.textSize *= available / label.measureText(amount)
        canvas.drawText(amount, cx, cy, label)
        canvas.drawText(context.getString(R.string.interaction_total), cx, cy + 16f * density, caption)
    }
}

internal class InteractionSplitView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var fraction: Float? = null
    fun setSplit(originals: Int, replies: Int) {
        fraction = originalFraction(originals, replies)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = height / 2f
        path.reset()
        path.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        paint.color = context.getColor(R.color.oneui_divider)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        fraction?.let { f ->
            paint.color = context.getColor(R.color.interaction_original_color)
            canvas.drawRect(0f, 0f, width * f, height.toFloat(), paint)
            paint.color = context.getColor(R.color.interaction_quotes_color)
            canvas.drawRect(width * f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.color = context.getColor(R.color.oneui_card_bg)
        val tick = resources.displayMetrics.density
        canvas.drawRect(width / 2f - tick, 0f, width / 2f + tick, height.toFloat(), paint)
        canvas.restoreToCount(save)
    }
}
