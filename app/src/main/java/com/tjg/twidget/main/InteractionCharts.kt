package com.tjg.twidget.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
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
        val stroke = size / 6f
        val radius = (size - stroke - 4f * density).coerceAtLeast(0f) / 2f
        val cx = width / 2f; val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.strokeWidth = stroke
        // Tiny categories need a visible rounded segment, as in the design.
        // The legend and accessibility descriptions retain the exact counts.
        val sum = values.sumOf { it.coerceAtLeast(0).toDouble() }
        val visible = values.map { if (it <= 0 || sum == 0.0) 0L else (maxOf(it / sum, .06) * 1_000_000).toLong() }
        val segments = donutSegments(visible, 5f)
        if (segments.isEmpty()) {
            paint.color = context.getColor(R.color.oneui_divider)
            canvas.drawArc(bounds, 0f, 360f, false, paint)
        }
        segments.forEach { segment ->
            val gradients = listOf(
                intArrayOf(0xFFD6544D.toInt(), 0xFFFF9791.toInt()),
                intArrayOf(0xFF0FCF6E.toInt(), 0xFF8BE6B8.toInt()),
                intArrayOf(0xFF0381FE.toInt(), 0xFFC7DBEF.toInt()),
                intArrayOf(0xFFE65B17.toInt(), 0xFFF3A680.toInt()),
            )
            // Fill a rounded annular wedge instead of shrinking the stroke.
            // Small slices retain the same radial thickness as the large slice.
            val start = segment.start + 180f
            val sweep = segment.sweep
            val outer = radius + stroke / 2f
            val inner = radius - stroke / 2f
            val corner = minOf(stroke / 2f, inner * Math.toRadians(sweep.toDouble()).toFloat() / 2f)
            val outerInset = Math.toDegrees((corner / outer).toDouble()).toFloat()
            val innerInset = Math.toDegrees((corner / inner).toDouble()).toFloat()
            fun x(r: Float, angle: Float) = cx + r * kotlin.math.cos(Math.toRadians(angle.toDouble())).toFloat()
            fun y(r: Float, angle: Float) = cy + r * kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat()
            val end = start + sweep
            val wedge = Path().apply {
                moveTo(x(outer, start + outerInset), y(outer, start + outerInset))
                arcTo(RectF(cx-outer, cy-outer, cx+outer, cy+outer), start+outerInset, (sweep-2*outerInset).coerceAtLeast(0f))
                quadTo(x(outer,end), y(outer,end), x(outer-corner,end), y(outer-corner,end))
                lineTo(x(inner+corner,end), y(inner+corner,end))
                quadTo(x(inner,end), y(inner,end), x(inner,end-innerInset), y(inner,end-innerInset))
                arcTo(RectF(cx-inner,cy-inner,cx+inner,cy+inner), end-innerInset, -(sweep-2*innerInset).coerceAtLeast(0f))
                quadTo(x(inner,start), y(inner,start), x(inner+corner,start), y(inner+corner,start))
                lineTo(x(outer-corner,start), y(outer-corner,start))
                quadTo(x(outer,start), y(outer,start), x(outer,start+outerInset), y(outer,start+outerInset))
                close()
            }
            // Include the full wedge thickness. The centreline can be almost
            // horizontal for a small slice, compressing its gradient to a seam.
            val gradientBounds = RectF()
            wedge.computeBounds(gradientBounds, true)
            paint.shader = LinearGradient(0f, gradientBounds.top, 0f,
                gradientBounds.bottom.coerceAtLeast(gradientBounds.top + 1f),
                gradients[segment.index][0], gradients[segment.index][1], Shader.TileMode.CLAMP)
            paint.style = Paint.Style.FILL
            canvas.drawPath(wedge, paint)
            paint.style = Paint.Style.STROKE
        }
        paint.shader = null
    }
}

internal class InteractionSplitView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var fraction: Float? = null
    fun setSplit(originals: Int, replies: Int) = setValues(originals.toLong(), replies.toLong())
    fun setValues(left: Long?, right: Long?) {
        val total = (left ?: 0).toDouble() + (right ?: 0).toDouble()
        fraction = if (left == null || right == null || left < 0 || right < 0 || total <= 0) null else (left / total).toFloat()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val gap = minOf(6f * density, w * .04f)
        val inside = minOf(6f * density, h / 2f)
        val outside = h / 2f
        val save = canvas.save()
        if (layoutDirection == LAYOUT_DIRECTION_RTL) { canvas.translate(w, 0f); canvas.scale(-1f, 1f) }
        // Clip the whole bar as one pill; tiny ratios must not become separate pills.
        path.reset()
        path.addRoundRect(RectF(0f, 0f, w, h), outside, outside, Path.Direction.CW)
        canvas.clipPath(path)
        val f = fraction
        if (f == null) {
            paint.color = context.getColor(R.color.oneui_divider)
            canvas.drawRoundRect(0f, 0f, w, h, outside, outside, paint)
        } else {
            val actualGap = if (f > 0f && f < 1f) gap else 0f
            val left = (w - actualGap) * f
            fun segment(start: Float, end: Float, first: Boolean, from: Int, to: Int) {
                if (end <= start) return
                val inner = if (f == 0f || f == 1f) 0f else minOf(inside, (end - start) / 2f)
                path.reset()
                val a = if (first) 0f else inner
                val b = if (first) inner else 0f
                path.addRoundRect(RectF(start, 0f, end, h), floatArrayOf(a,a,b,b,b,b,a,a), Path.Direction.CW)
                paint.shader = LinearGradient(start, 0f, end, 0f, from, to, Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint)
            }
            segment(0f, left, true, 0xFF4C82F6.toInt(), 0xFF81A0E0.toInt())
            segment(left + actualGap, w, false, 0xFF9CDEBD.toInt(), 0xFF0FCF6E.toInt())
            paint.shader = null
        }
        canvas.restoreToCount(save)
    }
}
