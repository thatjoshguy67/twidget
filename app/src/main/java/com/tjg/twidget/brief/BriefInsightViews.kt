package com.tjg.twidget.brief

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetFonts

/** Existing Brief shells and dashboard charts, with one visual takeaway per insight. */
internal object BriefInsightViews {
    fun title(context: Context, type: BriefCardType): String = context.getString(when (type) {
        BriefCardType.AUDIENCE_MOMENTUM -> R.string.brief_content_momentum
        BriefCardType.CONVERSATION_PULSE -> R.string.brief_content_conversations
        BriefCardType.CONTENT_PATTERN -> R.string.brief_content_patterns
        BriefCardType.WEEKLY_RECAP -> R.string.brief_content_recap
        BriefCardType.TOPIC_SPOTLIGHT -> R.string.brief_content_topics
        else -> R.string.brief_compact_experiment_title
    })
    fun create(context: Context, card: BriefCard, compose: () -> Unit): View = BriefReviewViews.card(context).apply {
        setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 16))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(BriefReviewViews.text(context, title(context, card.type), 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.ImageButton(context).apply {
                setImageResource(dev.oneuiproject.oneui.R.drawable.ic_oui_info_outline)
                imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.oneui_text_secondary))
                setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
                val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
                setBackgroundResource(attrs.getResourceId(0, 0)); attrs.recycle()
                contentDescription = context.getString(R.string.brief_compact_details, title(context, card.type))
                setOnClickListener {
                    val detail = buildString {
                        append(card.body)
                        if (card.type == BriefCardType.AUDIENCE_MOMENTUM || card.type == BriefCardType.WEEKLY_RECAP) {
                            append("\n\n"); append(context.getString(R.string.brief_chart_observed_followers))
                            if (card.type == BriefCardType.AUDIENCE_MOMENTUM) { append("\n"); append(context.getString(R.string.brief_compact_week_legend)) }
                        }
                        if (card.type == BriefCardType.CONTENT_PATTERN || card.type == BriefCardType.TOPIC_SPOTLIGHT) {
                            append("\n\n"); append(context.getString(R.string.brief_chart_median_views))
                            card.chartPoints.forEach { point ->
                                append("\n"); append(point.label); append(": ")
                                append(context.getString(R.string.brief_chart_sample_size, point.samples))
                            }
                        }
                    }
                    androidx.appcompat.app.AlertDialog.Builder(context).setTitle(title(context, card.type)).setMessage(detail)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }, LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)))
        })
        when (card.type) {
            BriefCardType.AUDIENCE_MOMENTUM -> {
                val current = card.metrics.firstOrNull()
                val previous = card.metrics.getOrNull(1)
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(BriefReviewViews.text(context, context.getString(R.string.brief_compact_this_week, current?.value.orEmpty()), 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(BriefReviewViews.text(context, context.getString(R.string.brief_compact_previous_week, previous?.value.orEmpty()), 12f, secondary = true))
                }, params(context, 2))
                addView(BriefTrendView(context, card.chartPoints, splitWeeks = true), params(context, 8, 100))
            }
            BriefCardType.WEEKLY_RECAP -> {
                val count = card.metrics.getOrNull(1)?.value.orEmpty()
                val views = card.metrics.getOrNull(2)?.value.orEmpty()
                addView(BriefReviewViews.text(context, context.getString(R.string.brief_compact_recap_sample, count, views), 13f, secondary = true), params(context, 2))
                if (card.activityPoints.isNotEmpty()) addView(BriefReviewViews.text(context,
                    "${card.activityPoints.first().label} – ${card.activityPoints.last().label}", 12f, secondary = true), params(context, 8))
                addView(BriefDailyActivityView(context, card.activityPoints), params(context, 6, 110))
                card.metrics.firstOrNull()?.let {
                    addView(BriefReviewViews.text(context, context.getString(R.string.brief_compact_followers, it.value), 13f), params(context, 4))
                }
            }
            BriefCardType.CONTENT_EXPERIMENT -> {
                card.chartPoints.firstOrNull()?.let {
                    addView(BriefReviewViews.text(context, context.getString(R.string.brief_chart_try_format, it.label), 16f), params(context, 4))
                }
                addView(BriefReviewViews.button(context, R.string.brief_chart_compose, click = compose).apply {
                    isEnabled = card.action != BriefCardAction.NONE
                }, params(context, 12))
            }
            else -> {
                if (card.type != BriefCardType.CONVERSATION_PULSE) {
                    addView(BriefReviewViews.text(context, context.getString(R.string.brief_compact_median_views), 12f, secondary = true), params(context, 0))
                }
                val maximum = card.chartPoints.mapNotNull { it.value }.maxOrNull() ?: 0
                card.chartPoints.forEachIndexed { index, point ->
                    val accent = context.getColor(R.color.oneui_accent)
                    val color = if (index == 0) accent else androidx.core.graphics.ColorUtils.blendARGB(accent, context.getColor(R.color.oneui_text_secondary), .35f)
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(BriefReviewViews.text(context, point.label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                        addView(BriefReviewViews.text(context, com.tjg.twidget.core.AppLocales.integer(point.value ?: 0), 16f, bold = true).apply {
                            setPadding(dp(context, 8), 0, 0, 0)
                        })
                    }, params(context, 10))
                    addView(InsightBar(context, BriefInsightChartPolicy.fraction(point.value ?: 0, maximum), color), params(context, 5, 8))
                }
                if (card.type == BriefCardType.CONVERSATION_PULSE) card.metrics.firstOrNull()?.let {
                    addView(BriefReviewViews.text(context, context.getString(R.string.brief_compact_conversation_rate, it.value), 12f, secondary = true), params(context, 10))
                }
                if (card.type == BriefCardType.CONTENT_PATTERN || card.type == BriefCardType.TOPIC_SPOTLIGHT) {
                    val total = card.chartPoints.sumOf { it.samples }
                    addView(BriefReviewViews.text(context, context.getString(R.string.brief_compact_sample, total), 12f, secondary = true), params(context, 10))
                }
            }
        }
    }
    private fun params(context: Context, top: Int, height: Int = -2) = LinearLayout.LayoutParams(-1, if (height < 0) height else dp(context, height)).apply { topMargin = dp(context, top) }
    private fun dp(context: Context, value: Int) = BriefReviewViews.dp(context, value)
}

internal object BriefInsightChartPolicy {
    fun fraction(value: Long, maximum: Long): Float = if (maximum <= 0) 0f else (value.toDouble() / maximum).coerceIn(0.0, 1.0).toFloat()
    fun heightFraction(value: Long, minimum: Long, maximum: Long): Float =
        if (maximum <= minimum) .5f else ((value.toDouble() - minimum) / (maximum.toDouble() - minimum)).coerceIn(0.0, 1.0).toFloat()
}

private class InsightBar(context: Context, private val fraction: Float, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fill: android.graphics.Shader? = null
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val length = (w * fraction).coerceAtLeast(1f)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fill = android.graphics.LinearGradient(if (rtl) w.toFloat() else 0f, 0f,
            if (rtl) w - length else length, 0f, color, 0xff85a3de.toInt(), android.graphics.Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.shader = null
        paint.color = context.getColor(R.color.oneui_divider)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (fraction > 0) {
            if (layoutDirection == LAYOUT_DIRECTION_RTL) rect.left = width * (1 - fraction) else rect.right = width * fraction
            paint.shader = fill
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }
    }
}

/** Gaps remain gaps: missing follower observations are never zero or interpolated. */
private class BriefTrendView(context: Context, private val points: List<BriefChartPoint>, private val splitWeeks: Boolean = false) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val accent = context.getColor(R.color.oneui_accent)
    private val secondary = context.getColor(R.color.oneui_text_secondary)
    private val strings = BriefStrings.from(context)
    init {
        contentDescription = points.joinToString(". ") { "${it.label}: ${it.value?.let(com.tjg.twidget.core.AppLocales::integer) ?: "—"}" }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        paint.typeface = TwidgetFonts.forApp(context, 400)
    }
    override fun onDraw(canvas: Canvas) {
        val known = points.mapNotNull { it.value }
        if (known.isEmpty() || points.size < 2) return
        val minimum = known.min(); val maximum = known.max()
        val left = 48 * density; val right = width - 5 * density
        val top = 9 * density; val bottom = height - 28 * density
        val middle = points.lastIndex / 2
        fun x(index: Int) = left + (right - left) * index / points.lastIndex
        fun y(value: Long) = bottom - (bottom - top) * BriefInsightChartPolicy.heightFraction(value, minimum, maximum)
        paint.alpha = 255
        paint.color = context.getColor(R.color.oneui_divider); paint.strokeWidth = density
        canvas.drawLine(left, bottom, right, bottom, paint)
        val gradient = android.graphics.LinearGradient(left, top, right, bottom, accent, 0xff85a3de.toInt(), android.graphics.Shader.TileMode.CLAMP)
        points.forEachIndexed { index, point ->
            point.value?.let { value ->
                val previousWeek = splitWeeks && index <= middle
                paint.color = accent
                paint.alpha = if (previousWeek) 95 else 255
                paint.shader = gradient
                paint.strokeWidth = 2.5f * density
                val previous = points.getOrNull(index - 1)?.value
                if (previous != null) canvas.drawLine(x(index - 1), y(previous), x(index), y(value), paint)
                canvas.drawCircle(x(index), y(value), 3f * density, paint)
            }
        }
        paint.shader = null
        paint.alpha = 255
        paint.color = secondary
        paint.textSize = 10f * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(strings.compactNumber(maximum), left - 8 * density, top + paint.textSize / 2, paint)
        canvas.drawText(strings.compactNumber(minimum), left - 8 * density, bottom, paint)
        listOf(0, middle, points.lastIndex).distinct().forEach { index ->
            paint.textAlign = when (index) { 0 -> Paint.Align.LEFT; points.lastIndex -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(points[index].label, x(index), height - 4 * density, paint)
        }
    }
}

/** Daily sampled tweet counts: a zero baseline, exact integer labels, and no decorative bands. */
private class BriefDailyActivityView(context: Context, private val points: List<BriefChartPoint>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private val strings = BriefStrings.from(context)
    private val accent = context.getColor(R.color.oneui_accent)
    private val secondary = context.getColor(R.color.oneui_text_secondary)
    init {
        contentDescription = points.joinToString(". ") { "${it.label}: ${it.value?.let(strings::number) ?: "—"}" }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        paint.typeface = TwidgetFonts.forApp(context, 400)
    }
    override fun onDraw(canvas: Canvas) {
        if (points.isEmpty()) return
        val maximum = points.mapNotNull { it.value }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val top = 22 * density
        val baseline = height - 25 * density
        val stride = width.toFloat() / points.size
        val barWidth = minOf(20 * density, stride * .5f)
        paint.shader = null
        paint.color = context.getColor(R.color.oneui_divider)
        paint.strokeWidth = density
        canvas.drawLine(0f, baseline, width.toFloat(), baseline, paint)
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("d", strings.locale)
        val valueSize = 12 * resources.displayMetrics.scaledDensity
        val dateSize = 10 * resources.displayMetrics.scaledDensity
        points.forEachIndexed { index, point ->
            val center = stride * (index + .5f)
            val value = point.value
            val y = baseline - (baseline - top) * BriefInsightChartPolicy.fraction(value ?: 0L, maximum)
            if (value != null && value > 0L) {
                rect.set(center - barWidth / 2, y, center + barWidth / 2, baseline)
                paint.shader = android.graphics.LinearGradient(0f, y, 0f, baseline, accent, 0xff85a3de.toInt(), android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
                paint.shader = null
                paint.color = context.getColor(R.color.oneui_text_primary)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = valueSize
                canvas.drawText(strings.number(value), center, y - 5 * density, paint)
            } else {
                paint.color = secondary
                if (value == null) {
                    paint.textSize = valueSize; paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("—", center, baseline - 4 * density, paint)
                } else canvas.drawCircle(center, baseline, 2 * density, paint)
            }
            val date = if (point.timestamp > 0) dateFormatter.format(java.time.Instant.ofEpochMilli(point.timestamp).atZone(java.time.ZoneId.systemDefault())) else point.label
            paint.color = secondary
            paint.textSize = dateSize
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(date, center, height - 4 * density, paint)
        }
    }
}
