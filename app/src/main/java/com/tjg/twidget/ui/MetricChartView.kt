package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedChartPoint
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.TwidgetStore
import java.text.NumberFormat
import java.util.Locale

class MetricChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_primary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = TwidgetFonts.oneUiSans(context, 700)
    }
    private val dateLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_primary)
        textSize = 10f * resources.displayMetrics.scaledDensity
        typeface = TwidgetFonts.oneUiSans(context, 700)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_divider)
        style = Paint.Style.FILL
        alpha = 105
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_card_bg)
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = TwidgetFonts.oneUiSans(context, 700)
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_primary)
        setShadowLayer(
            6f * resources.displayMetrics.density,
            0f,
            3f * resources.displayMetrics.density,
            Color.argb(90, 0, 0, 0),
        )
    }
    private val tooltipStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_accent)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val areaFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_divider)
        style = Paint.Style.FILL
        alpha = 35
    }
    private val areaStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_secondary)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        alpha = 55
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_secondary)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 105
    }
    private val linePath = Path()
    private val areaPath = Path()
    private val plotClipPath = Path()
    private val barRect = RectF()
    private val plotRect = RectF()
    private val tooltipRect = RectF()
    private val barHitBounds = mutableListOf<RectF>()
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private var labels: List<String> = emptyList()
    private var estimated: List<Boolean> = emptyList()
    private var values: List<Long> = emptyList()
    private var averageValues: List<Long> = emptyList()
    private var barGradient: LinearGradient? = null
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
    var onChartTapListener: (() -> Unit)? = null

    init {
        // Paint shadows on custom shapes require software rendering. The view
        // is small, and this keeps the tooltip visibly elevated in both themes.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
    }

    fun setData(samples: List<HistorySample>, selector: (HistorySample) -> Long) {
        labels = samples.map(HistorySample::dayLabel)
        estimated = samples.map(HistorySample::estimated)
        values = samples.map(selector)
        averageValues = emptyList()
        barHitBounds.clear()
        repeat(labels.size) { barHitBounds += RectF() }
        removeCallbacks(hideTooltipRunnable)
        activeIndex = -1
        invalidate()
    }

    /**
     * Sets the account-average series drawn behind the primary bars. Keeping
     * this separate prevents the current week from being presented as its own
     * historical average. An empty or misaligned series intentionally hides it.
     */
    fun setAverageSeries(values: List<Long>) {
        averageValues = values.takeIf { it.size == this.values.size } ?: emptyList()
        invalidate()
    }

    fun setSeries(points: List<ImportedChartPoint>) {
        labels = points.map(ImportedChartPoint::label)
        estimated = List(points.size) { false }
        values = points.map(ImportedChartPoint::value)
        averageValues = emptyList()
        barHitBounds.clear()
        repeat(labels.size) { barHitBounds += RectF() }
        removeCallbacks(hideTooltipRunnable)
        activeIndex = -1
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val density = resources.displayMetrics.density
        val top = 18f * density
        barGradient = LinearGradient(
            0f,
            top,
            0f,
            (height - 30f * density).coerceAtLeast(top + 1f),
            intArrayOf(Color.rgb(56, 122, 255), Color.rgb(133, 163, 222)),
            null,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (labels.isEmpty()) return

        val density = resources.displayMetrics.density
        val labelInset = 20f * density
        val right = 20f * density
        val top = 18f * density
        val bottomLabels = 30f * density
        val chartBottom = height - bottomLabels
        val chartHeight = chartBottom - top
        // The numbered axis belongs to the current week's bars. Including the
        // long-term account average here compresses small but real day-to-day
        // differences and makes every current bar appear maxed out.
        val (scaleMin, scaleMax) = MetricChartScale.primaryAxisBounds(values, averageValues)
        val scaleRange = (scaleMax - scaleMin).coerceAtLeast(1)
        val axisLabels = MetricChartScale.axisLabels(scaleMin, scaleMax)
        // Reserve a real gutter after the widest formatted Y label. A fixed
        // plot origin collapses this gap as soon as values gain commas/digits.
        val left = labelInset + axisLabels.maxOf(axisLabelPaint::measureText) + 8f * density
        val widthAvailable = width - left - right
        val barSlot = widthAvailable / labels.size
        val barWidth = (barSlot * 0.54f).coerceIn(6f * density, 24f * density)
        fun heightFor(value: Long): Float {
            val normalized = (value - scaleMin).coerceAtLeast(0)
            return if (value <= 0) {
                4f * density
            } else {
                (chartHeight * normalized / scaleRange).coerceIn(14f * density, chartHeight)
            }
        }
        fun yFor(value: Long): Float = top + chartHeight * MetricChartScale.yFraction(value, scaleMin, scaleMax)
        val averageBounds = MetricChartScale.axisBounds(averageValues)
        fun averageYFor(value: Long): Float = top + chartHeight * MetricChartScale.yFraction(
            value,
            averageBounds.first,
            averageBounds.second,
        )

        // Draw only as many x-labels as actually fit, keeping the newest one.
        val maxLabelWidth = labels.maxOf { dateLabelPaint.measureText(it) } + 2f * density
        val labelStep = kotlin.math.ceil(maxLabelWidth / barSlot).toInt().coerceAtLeast(1)

        // Keep the plot as one softly rounded surface while axes and dates
        // remain outside the clipped region, matching the compact Figma card.
        plotRect.set(left, top, width - right, chartBottom)
        val plotCheckpoint = canvas.save()
        plotClipPath.reset()
        plotClipPath.addRoundRect(plotRect, 16f * density, 16f * density, Path.Direction.CW)
        canvas.clipPath(plotClipPath)

        // The Figma graph uses four alternating rear columns rather than
        // horizontal rules, keeping the average line and bars easy to scan.
        labels.indices.filter { it % 2 == 0 }.forEach { index ->
            val columnLeft = left + index * barSlot
            canvas.drawRect(columnLeft, top, columnLeft + barSlot, chartBottom, gridPaint)
        }

        // The linked Figma graph uses the account average as a light,
        // diagonal-hatched area behind the primary current-week bars.
        linePath.reset()
        areaPath.reset()
        averageValues.firstOrNull()?.let { firstValue ->
            val firstY = averageYFor(firstValue)
            linePath.moveTo(left, firstY)
            areaPath.moveTo(left, firstY)
        }
        averageValues.forEachIndexed { index, value ->
            val x = left + index * barSlot + barSlot / 2f
            val y = averageYFor(value)
            linePath.lineTo(x, y)
            areaPath.lineTo(x, y)
        }
        if (averageValues.size > 1) {
            val plotRight = width - right
            val lastY = averageYFor(averageValues.last())
            linePath.lineTo(plotRight, lastY)
            areaPath.lineTo(plotRight, lastY)
            areaPath.lineTo(plotRight, chartBottom)
            areaPath.lineTo(left, chartBottom)
            areaPath.close()
            canvas.drawPath(areaPath, areaFillPaint)
            val checkpoint = canvas.save()
            canvas.clipPath(areaPath)
            val stripeGap = 6f * density
            var stripeX = left - chartHeight
            while (stripeX < width - right) {
                canvas.drawLine(stripeX, chartBottom, stripeX + chartHeight, top, areaStripePaint)
                stripeX += stripeGap
            }
            canvas.restoreToCount(checkpoint)
            canvas.drawPath(linePath, linePaint)
        }

        barPaint.shader = barGradient

        labels.forEachIndexed { index, _ ->
            val value = values[index]
            val barHeight = heightFor(value)
            val x = left + index * barSlot + (barSlot - barWidth) / 2f
            barRect.set(x, chartBottom - barHeight, x + barWidth, chartBottom)
            barHitBounds[index].set(left + index * barSlot, top, left + (index + 1) * barSlot, chartBottom)
            // Estimated (interpolated) bars render translucent so real data
            // reads solid at a glance.
            barPaint.alpha = if (estimated[index]) 80 else 255
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
            barPaint.alpha = 255
        }
        barPaint.shader = null
        canvas.restoreToCount(plotCheckpoint)

        axisLabels.forEachIndexed { index, label ->
            val y = top + chartHeight * index / (axisLabels.size - 1).coerceAtLeast(1)
            canvas.drawText(label, labelInset, y + 4f * density, axisLabelPaint)
        }
        labels.forEachIndexed { index, label ->
            if ((labels.lastIndex - index) % labelStep == 0) {
                val x = left + index * barSlot + (barSlot - barWidth) / 2f
                val labelWidth = dateLabelPaint.measureText(label)
                val labelX = (x + barWidth / 2f - labelWidth / 2f)
                    .coerceIn(left - labelWidth / 2f, width - right - labelWidth)
                canvas.drawText(label, labelX, height - 8f * density, dateLabelPaint)
            }
        }

        // Tooltips are an overlay. Drawing one inside the bar loop lets later
        // bars paint over it, which clips the bubble on selected early days.
        if (activeIndex in labels.indices) {
            val label = labels[activeIndex]
            val value = values[activeIndex]
            val x = left + activeIndex * barSlot + (barSlot - barWidth) / 2f
            drawTooltip(
                canvas = canvas,
                label = "$label  ${if (estimated[activeIndex]) "~" else ""}${numberFormat.format(value)}",
                anchorX = x + barWidth / 2f,
                anchorY = yFor(value),
                chartLeft = left,
                chartRight = width - right,
                chartTop = top,
                density = density,
            )
        }
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
                if (!touchMoved) {
                    val hitBar = barHitBounds.any { it.contains(event.x, event.y) }
                    if (hitBar) {
                        updateActiveBar(event.x, event.y)
                        postDelayed(hideTooltipRunnable, TOUCH_TOOLTIP_TIMEOUT_MS)
                    } else {
                        onChartTapListener?.invoke()
                    }
                }
                performClick()
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
        canvas.drawRoundRect(tooltipRect, 14f * density, 14f * density, tooltipStrokePaint)
        canvas.drawText(
            label,
            rectLeft + horizontalPadding,
            rectTop + verticalPadding - tooltipTextPaint.fontMetrics.ascent,
            tooltipTextPaint,
        )
    }

    private companion object {
        private const val TOUCH_TOOLTIP_TIMEOUT_MS = 1_500L
    }
}

internal object MetricChartScale {
    /** Background trends must never distort the numbered scale for the bars. */
    fun primaryAxisBounds(
        primary: List<Long>,
        @Suppress("UNUSED_PARAMETER") background: List<Long>,
    ): Pair<Long, Long> = axisBounds(primary)

    // Picks a [min, max] window for the y-axis. The highest observed value is
    // always the top gridline, so the bars use all available vertical space.
    // Near-flat cumulative counts keep a three-step zoomed window beneath it.
    fun axisBounds(values: List<Long>): Pair<Long, Long> {
        val rawMax = (values.maxOrNull() ?: 0).coerceAtLeast(0)
        val rawMin = (values.minOrNull() ?: 0).coerceAtLeast(0)
        val spread = rawMax - rawMin
        val zoom = rawMin > 0 && spread > 0 && spread <= rawMax / 3
        if (!zoom) return 0L to rawMax.coerceAtLeast(3)

        val step = niceStep((spread * 1.5 / 3).coerceAtLeast(1.0))
        val min = (rawMax - step * 3).coerceAtLeast(0)
        return min to rawMax
    }

    fun axisLabels(min: Long, max: Long): List<String> {
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

    fun yFraction(value: Long, min: Long, max: Long): Float {
        val range = (max - min).coerceAtLeast(1)
        val clamped = value.coerceIn(min, max)
        return (max - clamped).toFloat() / range
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

}
