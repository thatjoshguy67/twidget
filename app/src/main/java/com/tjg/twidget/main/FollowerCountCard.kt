package com.tjg.twidget.main

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetFonts
import java.text.NumberFormat

/** The count and the history graph are independent cards, backed by the same snapshot. */
internal object FollowerCountCard {
    fun create(context: Context, stats: ProfileStats, delta: Long, span: Int, existing: View? = null, catalogue: Boolean = false): View {
        val count = if (stats.followersKnown) NumberFormat.getIntegerInstance(AppLocales.applicationLocale())
            .format(stats.followersCount) else "--"
        val change = if (stats.followersKnown) TwidgetStore.signedNumber(delta) else "--"
        val color = context.getColor(if (delta < 0) R.color.metric_red else R.color.dashboard_count_editorial_delta)
        val root = existing ?: LayoutInflater.from(context).inflate(if (span == 2)
            R.layout.metric_card_follower_count_large else R.layout.metric_card_follower_count, null, false)
        root.findViewById<TextView>(R.id.followers_value).apply {
            val compact = span == 1
            text = editorialCopy(context, stats, change, color, compact, catalogue)
            // Let the compact paragraph use the whole tile even when the number
            // contains fewer words; the previous 35sp cap left a large empty band.
            val size = if (catalogue) if (compact) 25f else 30f else if (compact) 60f else 51f
            val lineHeight = if (compact && !catalogue) null else if (catalogue) if (compact) 28f else 36f else 57f
            maxLines = if (compact) 5 else 3
            setTag(R.id.dashboard_design_line_height, lineHeight)
            if (lineHeight == null) setLineSpacing(0f, 1f)
            fontFeatureSettings = "'dlig' 1, 'lnum' 1, 'pnum' 1"
            com.tjg.twidget.ui.TwidgetFonts.setRole(this, com.tjg.twidget.ui.TwidgetFonts.Role.DASHBOARD_EDITORIAL)
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(this, androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            textSize = size
            if (catalogue && lineHeight != null) androidx.core.widget.TextViewCompat.setLineHeight(this,
                android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, lineHeight, resources.displayMetrics).toInt())
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, if (compact) 10 else 14, size.toInt(), 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        if (catalogue) root.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
        root.contentDescription = "${context.getString(R.string.follower_count)}. $count. $change"
        return root
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun editorialCopy(context: Context, stats: ProfileStats, delta: String, deltaColor: Int, compact: Boolean, catalogue: Boolean): CharSequence {
        val locale = AppLocales.applicationLocale()
        val spelled = when {
            !stats.followersKnown -> "--"
            locale.language == "en" -> followerNumberWords(stats.followersCount.coerceAtLeast(0))
            else -> NumberFormat.getIntegerInstance(locale).format(stats.followersCount)
        }
        val words = spelled.split(Regex("\\s+")).filter(String::isNotEmpty)
        val gapRatio = if (compact) 4f / 35f else if (catalogue) 6f / 30f else 6f / 51f
        return SpannableStringBuilder().apply {
            words.forEachIndexed { index, word ->
                val key = word.lowercase(locale).trimEnd(',')
                val conjunction = key == "and"
                val scaleWord = key in setOf("hundred", "thousand", "million", "billion", "trillion")
                val weight = if (conjunction || scaleWord) 600 else 900
                val width = when {
                    conjunction -> 78
                    key == "hundred" -> 100
                    scaleWord -> if (compact) 25 else 76
                    compact && index == 0 -> 97
                    index == words.lastIndex -> if (compact) 118 else 151
                    key.endsWith("ty") -> if (compact) 110 else 82
                    else -> 118
                }
                val label = if (conjunction) word else word.replaceFirstChar { it.titlecase(locale) }
                appendStyled(label, TwidgetFonts.editorialTypeface(context, weight, width, scaleWord && key != "hundred"), gapRatio = gapRatio)
            }
            appendStyled(context.getString(R.string.followers), TwidgetFonts.editorialTypeface(context, 400, 69),
                context.getColor(R.color.oneui_text_secondary), gapRatio)
            appendStyled(delta, TwidgetFonts.editorialTypeface(context, 400, 57, roundness = 100), deltaColor, gapRatio)
        }
    }

    private fun SpannableStringBuilder.appendStyled(text: String, face: Typeface, color: Int? = null, gapRatio: Float) {
        if (isNotEmpty()) {
            val gap = length
            append(' ')
            setSpan(object : android.text.style.ReplacementSpan() {
                override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int,
                    fm: android.graphics.Paint.FontMetricsInt?): Int = (paint.textSize * gapRatio).toInt()
                override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float,
                    top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) = Unit
            }, gap, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val start = length
        append(text)
        setSpan(object : MetricAffectingSpan() {
            override fun updateDrawState(paint: TextPaint) { paint.typeface = face }
            override fun updateMeasureState(paint: TextPaint) { paint.typeface = face }
        }, start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        color?.let { setSpan(ForegroundColorSpan(it), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}
