package com.tjg.twidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.text.NumberFormat
import java.util.Locale

class MetricChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_secondary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sec", Typeface.BOLD)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_divider)
        strokeWidth = resources.displayMetrics.density
        alpha = 170
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sec", Typeface.BOLD)
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_accent)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()
    private val tooltipRect = RectF()
    private val barHitBounds = mutableListOf<RectF>()
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private var samples: List<HistorySample> = emptyList()
    private var selector: (HistorySample) -> Long = { it.followers }
    private var activeIndex = -1
    private val hideTooltipRunnable = Runnable { clearActiveBar() }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressRunnable = Runnable {
        touchMoved = true
        performLongClick()
    }
    private var downX = 0f
    private var downY = 0f
    private var touchMoved = false

    init {
        isClickable = true
        isFocusable = true
    }

    fun setData(samples: List<HistorySample>, selector: (HistorySample) -> Long) {
        this.samples = samples
        this.selector = selector
        removeCallbacks(hideTooltipRunnable)
        activeIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.isEmpty()) return

        val density = resources.displayMetrics.density
        val labelInset = 18f * density
        val left = 52f * density
        val right = 24f * density
        val top = 18f * density
        val bottomLabels = 34f * density
        val chartBottom = height - bottomLabels
        val chartHeight = chartBottom - top
        val widthAvailable = width - left - right
        val values = samples.map(selector)
        val (scaleMin, scaleMax) = axisBounds(values)
        val scaleRange = (scaleMax - scaleMin).coerceAtLeast(1)
        val labels = axisLabels(scaleMin, scaleMax)
        val barSlot = widthAvailable / samples.size
        val barWidth = (barSlot * 0.56f).coerceIn(18f * density, 32f * density)
        barHitBounds.clear()

        // Draw only as many x-labels as actually fit, keeping the newest one.
        val maxLabelWidth = samples.maxOf { labelPaint.measureText(it.dayLabel) } + 6f * density
        val labelStep = kotlin.math.ceil(maxLabelWidth / barSlot).toInt().coerceAtLeast(1)

        labels.forEachIndexed { index, label ->
            val y = top + chartHeight * index / (labels.size - 1).coerceAtLeast(1)
            canvas.drawLine(left, y, width - right, y, gridPaint)
            canvas.drawText(label, labelInset, y + 4f * density, labelPaint)
        }

        barPaint.shader = LinearGradient(
            0f,
            top,
            0f,
            chartBottom,
            intArrayOf(Color.rgb(57, 125, 244), Color.rgb(110, 156, 239)),
            null,
            Shader.TileMode.CLAMP,
        )

        samples.forEachIndexed { index, sample ->
            val value = selector(sample)
            val normalized = (value - scaleMin).coerceAtLeast(0)
            val barHeight = if (value <= 0) {
                4f * density
            } else {
                (chartHeight * normalized / scaleRange).coerceIn(14f * density, chartHeight)
            }
            val x = left + index * barSlot + (barSlot - barWidth) / 2f
            barRect.set(x, chartBottom - barHeight, x + barWidth, chartBottom)
            barHitBounds += RectF(left + index * barSlot, top, left + (index + 1) * barSlot, chartBottom)
            // Estimated (interpolated) bars render translucent so real data
            // reads solid at a glance.
            barPaint.alpha = if (sample.estimated) 80 else 255
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
            barPaint.alpha = 255
            if ((samples.lastIndex - index) % labelStep == 0) {
                val labelWidth = labelPaint.measureText(sample.dayLabel)
                val labelX = (x + barWidth / 2f - labelWidth / 2f)
                    .coerceIn(left - labelWidth / 2f, width - right - labelWidth)
                canvas.drawText(sample.dayLabel, labelX, height - 7f * density, labelPaint)
            }

            if (index == activeIndex) {
                drawTooltip(
                    canvas = canvas,
                    label = "${sample.dayLabel}  ${if (sample.estimated) "~" else ""}${numberFormat.format(value)}",
                    anchorX = x + barWidth / 2f,
                    anchorY = chartBottom - barHeight,
                    chartLeft = left,
                    chartRight = width - right,
                    chartTop = top,
                    density = density,
                )
            }
        }
        barPaint.shader = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(hideTooltipRunnable)
                removeCallbacks(longPressRunnable)
                downX = event.x
                downY = event.y
                touchMoved = false
                if (isLongClickable) postDelayed(longPressRunnable, longPressTimeout)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(event.x - downX)
                val dy = kotlin.math.abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    touchMoved = true
                    removeCallbacks(longPressRunnable)
                    clearActiveBar()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                performClick()
                if (!touchMoved) {
                    updateActiveBar(event.x, event.y)
                    postDelayed(hideTooltipRunnable, TOUCH_TOOLTIP_TIMEOUT_MS)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                clearActiveBar()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                removeCallbacks(hideTooltipRunnable)
                updateActiveBar(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                clearActiveBar()
                return true
            }
        }
        return super.onHoverEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideTooltipRunnable)
        removeCallbacks(longPressRunnable)
        clearActiveBar()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != VISIBLE) {
            removeCallbacks(hideTooltipRunnable)
            removeCallbacks(longPressRunnable)
            clearActiveBar()
        }
        super.onWindowVisibilityChanged(visibility)
    }

    private fun updateActiveBar(x: Float, y: Float) {
        val nextIndex = barHitBounds.indexOfFirst { it.contains(x, y) }
        if (nextIndex != activeIndex) {
            activeIndex = nextIndex
            invalidate()
        }
    }

    private fun clearActiveBar() {
        if (activeIndex != -1) {
            activeIndex = -1
            invalidate()
        }
    }

    private fun drawTooltip(
        canvas: Canvas,
        label: String,
        anchorX: Float,
        anchorY: Float,
        chartLeft: Float,
        chartRight: Float,
        chartTop: Float,
        density: Float,
    ) {
        val horizontalPadding = 9f * density
        val verticalPadding = 6f * density
        val textWidth = tooltipTextPaint.measureText(label)
        val textHeight = tooltipTextPaint.fontMetrics.run { descent - ascent }
        val rectWidth = textWidth + horizontalPadding * 2f
        val rectHeight = textHeight + verticalPadding * 2f
        val rectLeft = (anchorX - rectWidth / 2f).coerceIn(chartLeft, chartRight - rectWidth)
        val rectTop = (anchorY - rectHeight - 8f * density).coerceAtLeast(chartTop)

        tooltipRect.set(rectLeft, rectTop, rectLeft + rectWidth, rectTop + rectHeight)
        canvas.drawRoundRect(tooltipRect, 14f * density, 14f * density, tooltipPaint)
        canvas.drawText(
            label,
            rectLeft + horizontalPadding,
            rectTop + verticalPadding - tooltipTextPaint.fontMetrics.ascent,
            tooltipTextPaint,
        )
    }

    // Picks a [min, max] window for the y-axis. For large near-flat cumulative
    // counts (e.g. 175,690..175,700 likes) it zooms into the active band so the
    // day-to-day variation is visible, but always keeps a wide enough span that
    // the four gridlines round to distinct labels. Otherwise it uses a
    // zero-based nice axis.
    private fun axisBounds(values: List<Long>): Pair<Long, Long> {
        val rawMax = (values.maxOrNull() ?: 0).coerceAtLeast(0)
        val rawMin = (values.minOrNull() ?: 0).coerceAtLeast(0)
        val spread = rawMax - rawMin
        val zoom = rawMin > 0 && spread > 0 && spread <= rawMax / 3
        if (!zoom) return 0L to niceMax(rawMax).coerceAtLeast(4)

        // Pad the band and snap to a step that yields 3 equal, distinct gaps.
        val step = niceStep((spread * 1.5 / 3).coerceAtLeast(1.0))
        val min = (rawMin / step) * step
        var max = ((rawMax / step) + 1) * step
        if (max - min < step * 3) max = min + step * 3
        return min to max
    }

    private fun axisLabels(min: Long, max: Long): List<String> {
        val values = listOf(max, min + (max - min) * 2 / 3, min + (max - min) / 3, min)
        val compact = values.map { TwidgetStore.compactNumber(it) }
        // If compacting collapses distinct gridlines into the same label
        // (175.7K == 175.7K), fall back to full grouped numbers.
        return if (compact.distinct().size < values.distinct().size) {
            values.map { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) }
        } else {
            compact
        }
    }

    private fun niceStep(value: Double): Long {
        if (value <= 1.0) return 1
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(value)))
        val normalized = value / magnitude
        val rounded = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return (rounded * magnitude).toLong().coerceAtLeast(1)
    }

    private fun niceMax(value: Long): Long {
        if (value <= 10) return 10
        val magnitude = Math.pow(10.0, (value.toString().length - 1).toDouble()).toLong()
        val normalized = value.toDouble() / magnitude
        val rounded = when {
            normalized <= 1.5 -> 1.5
            normalized <= 2.0 -> 2.0
            normalized <= 3.0 -> 3.0
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return (rounded * magnitude).toLong()
    }

    private companion object {
        private const val TOUCH_TOOLTIP_TIMEOUT_MS = 1_500L
    }
}
