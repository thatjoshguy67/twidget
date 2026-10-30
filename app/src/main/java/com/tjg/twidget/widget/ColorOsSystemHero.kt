package com.tjg.twidget.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.widget.RemoteViews
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import kotlin.math.ceil

/** Shares the hero fitting and emphasis rules, measuring native TextView span advances. */
internal object ColorOsSystemHero {
    private fun weightSpan(weight: Int) = if (Build.VERSION.SDK_INT >= 33)
        StyleSpan(Typeface.BOLD, weight - 700)
    else StyleSpan(if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)

    fun fit(context: Context, settings: TwidgetWidgetSettings, words: List<String>, countWords: Int,
        width: Int, height: Int): FollowerHeroLayout.Layout {
        val density = context.resources.displayMetrics.density
        val availableWidth = ((width - 24) * density).coerceAtLeast(2f)
        val availableHeight = ((height - 46) * density).coerceAtLeast(2f)
        val colors = WidgetColors.resolve(context, settings, false)
        return WidgetArtworkRenderer.followerHero(context, settings.copy(fontFamily = TwidgetStore.FONT_SYSTEM),
            words, countWords, colors.primary, colors.secondary, colors.primary) { paint, weight ->
                // Android synthesizes bold for adjusted StyleSpans below weight 600.
                // Include it in fitting, rather than sizing boxes from unstyled glyphs.
                val native = TextPaint(paint).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
                weightSpan(weight).updateMeasureState(native)
                paint.set(native)
            }
            .fit(availableWidth, availableHeight, (if (width - 24 < 230) 4f else 6f) * density, 4f * density)
    }

    fun bind(views: RemoteViews, context: Context, settings: TwidgetWidgetSettings, words: List<String>,
        countWords: Int, delta: Long, width: Int, height: Int) {
        val fitted = fit(context, settings, words, countWords, width, height)
        views.removeAllViews(R.id.coloros_native_hero)
        val system = settings.colorMode == TwidgetStore.COLOR_MODE_SYSTEM
        val dark = widgetUsesDarkTheme(settings.colorMode)
        fitted.lines.forEachIndexed { lineIndex, runs ->
            var x = -fitted.bounds[lineIndex].left
            val baseline = -fitted.bounds.first().top + lineIndex * fitted.advance
            runs.forEach { run ->
                val fm = run.paint.fontMetricsInt
                val word = RemoteViews(context.packageName, R.layout.widget_coloros_native_word)
                val text = SpannableString(run.text).apply {
                    val weight = if (Build.VERSION.SDK_INT >= 28) run.paint.typeface.weight else if (run.paint.isFakeBoldText) 700 else 400
                    val span = weightSpan(weight)
                    setSpan(span, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                word.setTextViewText(R.id.coloros_native_word, text)
                word.setTextViewTextSize(R.id.coloros_native_word, TypedValue.COMPLEX_UNIT_PX, fitted.size)
                word.setViewPadding(R.id.coloros_native_word, 2, 0, 2, 0)
                // The probe's host API requires Android 12+, which also supplies RemoteViews geometry setters.
                if (Build.VERSION.SDK_INT >= 31) {
                    word.setViewLayoutWidth(R.id.coloros_native_word, ceil(run.advance + 4), TypedValue.COMPLEX_UNIT_PX)
                    word.setViewLayoutHeight(R.id.coloros_native_word, (fm.descent - fm.ascent + 2).toFloat(), TypedValue.COMPLEX_UNIT_PX)
                    word.setViewLayoutMargin(R.id.coloros_native_word, RemoteViews.MARGIN_LEFT, x - 2, TypedValue.COMPLEX_UNIT_PX)
                    word.setViewLayoutMargin(R.id.coloros_native_word, RemoteViews.MARGIN_TOP, baseline + fm.ascent, TypedValue.COMPLEX_UNIT_PX)
                }
                fun color(night: Boolean): Int {
                    val colors = WidgetColors.resolve(context, settings, night)
                    return when (run.index) {
                        countWords -> android.graphics.Color.argb(153, android.graphics.Color.red(colors.primary), android.graphics.Color.green(colors.primary), android.graphics.Color.blue(colors.primary))
                        countWords + 1 -> colors.deltaColor(delta, night)
                        else -> colors.primary
                    }
                }
                if (Build.VERSION.SDK_INT >= 31 && system)
                    word.setColorStateList(R.id.coloros_native_word, "setTextColor", ColorStateList.valueOf(color(false)), ColorStateList.valueOf(color(true)))
                else word.setTextColor(R.id.coloros_native_word, color(dark))
                views.addView(R.id.coloros_native_hero, word)
                x += run.advance + fitted.wordGap
            }
        }
    }
}
