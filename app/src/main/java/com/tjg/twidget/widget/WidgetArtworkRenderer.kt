package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.ui.TwidgetFonts

object WidgetArtworkRenderer {
    private data class FontStyle(val family: String, val weight: Int, val width: Int, val roundness: Int, val slant: Int)
    // Variable typeface construction is expensive, especially on resize. Templates
    // are bounded and never mutated; each caller retains its own colour and size.
    private val fontPaints = LruCache<FontStyle, Paint>(256)

    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        stats: ProfileStats,
        settings: TwidgetWidgetSettings,
        mode: Int,
        dark: Boolean,
        delta: Long = 0,
        drawBackground: Boolean = false,
        bitmapBudgetBytes: Long = Long.MAX_VALUE,
    ): Bitmap {
        if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_2X1 || mode == TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP) {
            return renderCompact(context, widthPx, heightPx, stats, settings, mode, dark, delta, drawBackground, bitmapBudgetBytes)
        }
        val width = widthPx.coerceAtLeast(dp(context, 120))
        val height = heightPx.coerceAtLeast(dp(context, 120))
        val (bitmap, canvas) = widgetArtworkSurface(width, height, bitmapBudgetBytes)
        if (drawBackground) drawWidgetBackground(context, canvas, width, height, settings, dark)
        val density = context.resources.displayMetrics.density
        val colors = WidgetColors.resolve(context, settings, dark)
        val primary = colors.primary
        val secondary = colors.secondary
        val contained = settings.style == WidgetStyle.MATERIAL && settings.containedFooter
        val pad = (if (settings.style == WidgetStyle.MATERIAL && !dark) 10f else 12f) * density
        val footerPaint = textPaint(context, settings, secondary, bold = true).apply {
            textSize = 12f * density
            applyWidgetTypeface(context, settings.fontFamily, 600, if (contained) 25 else 51, 100)
        }
        val deltaText = if (!settings.showDelta || delta == 0L) "" else TwidgetStore.signedNumber(delta, AppLocales.resolve(settings.language))
        val deltaColor = if (delta < 0) Color.rgb(229, 83, 75) else Color.rgb(0, 170, 86)
        val footerHeight = (if (contained) 20f else 14f) * density
        val textMaxWidth = width - pad * 2
        val textMaxHeight = height - pad * 2 - footerHeight - 8f * density
        val locale = AppLocales.resolve(settings.language)
        val localizedContext = AppLocales.wrap(context, settings.language)
        val countWords = TwidgetWidget.followersInWords(stats.followersCount, locale)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        val words = countWords + localizedContext.getString(R.string.followers) + listOfNotNull(deltaText.takeIf { it.isNotEmpty() })
        val gap = wordSpacing(context, textMaxWidth)
        val hero = followerHero(context, settings, words, countWords.size, primary, secondary, deltaColor)
        hero.draw(canvas, textMaxWidth, textMaxHeight, pad, gap, 4f * density)

        val handle = "@${stats.userName}"
        val footerCenterY = height - pad - footerHeight / 2f
        val logoSize = context.resources.getDimension(R.dimen.widget_footer_logo_size)
        val chipPadding = if (contained) 6f * density else 0f
        val handleMaxWidth = (textMaxWidth - logoSize - 6f * density - chipPadding * 2).coerceAtLeast(1f)
        shrinkToFit(footerPaint, handle, handleMaxWidth)
        val handleX = pad + chipPadding + logoSize + 6f * density
        if (contained) {
            val chipWidth = chipPadding * 2 + logoSize + 6f * density + footerPaint.measureText(handle)
            canvas.drawRoundRect(RectF(pad, footerCenterY - 10f * density,
                pad + chipWidth, footerCenterY + 10f * density), 10f * density, 10f * density,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary })
        }
        val footerColor = if (contained) colors.background else secondary
        drawLogo(context, canvas, settings, footerColor, pad + chipPadding, footerCenterY - logoSize / 2f, logoSize)
        val footerBaseline = footerCenterY - (footerPaint.fontMetrics.ascent + footerPaint.fontMetrics.descent) / 2f
        canvas.drawText(handle, handleX, footerBaseline, footerPaint.apply { color = footerColor })
        return bitmap
    }

    private fun wordSpacing(context: Context, maxWidth: Float): Float =
        (if (maxWidth / context.resources.displayMetrics.density < 230f) 4f else 6f) * context.resources.displayMetrics.density

    internal fun followerHero(
        context: Context,
        settings: TwidgetWidgetSettings,
        words: List<String>,
        countWordCount: Int,
        primary: Int,
        secondary: Int,
        deltaColor: Int,
    ): FollowerHeroLayout {
        val flex = settings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX
        val natural = textPaint(context, settings, primary, bold = false).apply { textSize = 100f }
        // Use actual glyph advances, not spelling, character counts or a word dictionary.
        // Shorter runs can carry more emphasis without crowding their neighbours.
        val emphasis = words.map { (1f - (natural.measureText(it) / natural.textSize - 1.5f) / 4f).coerceIn(0f, 1f) }
        return FollowerHeroLayout(words) { index, fullness ->
            val supporting = index >= countWordCount
            val connector = words[index].equals("and", ignoreCase = true) || words[index].equals("und", ignoreCase = true)
            val weight = when {
                supporting || connector -> 200
                flex -> (500 + emphasis[index] * 330 + fullness * 70).toInt().coerceIn(450, 900)
                // A wider weight range gives non-width-variable fonts contrast too.
                // Squaring the measured emphasis keeps longer words visibly lighter.
                else -> (300 + emphasis[index] * emphasis[index] * 500 + fullness * 60)
                    .toInt().coerceIn(300, 800)
            }
            // The supporting line keeps the design's narrow proportions even when
            // count words open up to fill a short line. All runs share one size.
            val axisWidth = when (index) {
                countWordCount -> 69
                countWordCount + 1 -> 57
                else -> (100 + emphasis[index] * 18 + fullness * 30).toInt()
            }
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = when (index) {
                    countWordCount -> if (settings.style == WidgetStyle.MATERIAL) secondary else withAlpha(primary, 0.6f)
                    countWordCount + 1 -> deltaColor
                    else -> primary
                }
                applyWidgetTypeface(context, settings.fontFamily, weight, axisWidth,
                    googleRoundness = if (index > countWordCount) 100 else 0,
                    googleSlant = if (!supporting && words[index].endsWith(',')) -10 else 0)
            }
        }
    }

    // Numeric formats for the 2x1 and strip (3x1/4x1) sizes, drawn as bitmaps
    // because launchers ignore @font references when inflating RemoteViews.
    private fun renderCompact(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        stats: ProfileStats,
        settings: TwidgetWidgetSettings,
        mode: Int,
        dark: Boolean,
        delta: Long,
        drawBackground: Boolean,
        bitmapBudgetBytes: Long,
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val (bitmap, canvas) = widgetArtworkSurface(width, height, bitmapBudgetBytes)
        if (drawBackground) drawWidgetBackground(context, canvas, width, height, settings, dark)
        val colors = WidgetColors.resolve(context, settings, dark)
        val locale = AppLocales.resolve(settings.language)
        val value = AppLocales.integer(stats.followersCount, locale)
        val label = AppLocales.wrap(context, settings.language).getString(R.string.followers)
        val deltaText = if (!settings.showDelta || delta == 0L) "" else TwidgetStore.signedNumber(delta, locale)
        fun paint(weight: Int, color: Int, size: Float, axisWidth: Int, roundness: Int) =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.color = color
                textSize = size * density
                applyWidgetTypeface(context, settings.fontFamily, weight, axisWidth, roundness)
            }
        val valuePaint = paint(700, colors.primary, 26f, 110, 100)
        val labelPaint = paint(400, colors.secondary, 26f, 80, 0)
        val compact = mode == TwidgetWidget.LAYOUT_MODE_COMPACT_2X1
        val deltaPaint = paint(400, if (delta < 0) Color.rgb(255, 59, 48) else
            if (settings.style == WidgetStyle.MATERIAL) Color.rgb(12, 162, 86) else Color.rgb(46, 125, 50),
            if (compact && settings.style == WidgetStyle.ONE_UI) 20f else 26f, 57, 100)
        val gap = (if (compact) 6f else 10f) * density
        val logoSize = 26f * density
        fun lineWidth() = valuePaint.measureText(value) +
            (if (compact) logoSize + gap else gap + labelPaint.measureText(label)) +
            (if (deltaText.isEmpty()) 0f else gap + deltaPaint.measureText(deltaText))
        // Preserve the 26sp count by condensing its width first, as in the 999,999,999 design.
        if (settings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX && lineWidth() > width - 18f * density) {
            for (axisWidth in 109 downTo 25) {
                valuePaint.applyWidgetTypeface(context, settings.fontFamily, 700, axisWidth, 100)
                if (lineWidth() <= width - 18f * density) break
            }
        }
        // The logo and gaps stay fixed while the text shrinks into the remaining width.
        val availableWidth = width - 18f * density
        if (lineWidth() > availableWidth) {
            val fixedWidth = (if (compact) logoSize else 0f) +
                gap + (if (deltaText.isEmpty()) 0f else gap)
            val scale = ((availableWidth - fixedWidth) / (lineWidth() - fixedWidth)).coerceIn(0.01f, 1f)
            listOf(valuePaint, labelPaint, deltaPaint).forEach { it.textSize *= scale }
        }
        val blockHeight = if (compact) 26f * density else 50f * density
        val top = (height - blockHeight) / 2f
        val baseline = top + 13f * density - (valuePaint.fontMetrics.ascent + valuePaint.fontMetrics.descent) / 2f
        var x = (width - lineWidth()) / 2f
        if (compact) {
            drawLogo(context, canvas, settings, colors.secondary, x, (height - logoSize) / 2f, logoSize)
            x += logoSize + gap
        }
        canvas.drawText(value, x, baseline, valuePaint)
        x += valuePaint.measureText(value) + gap
        if (!compact) {
            canvas.drawText(label, x, baseline, labelPaint)
            x += labelPaint.measureText(label) + gap
        }
        if (deltaText.isNotEmpty()) canvas.drawText(deltaText, x, baseline, deltaPaint)
        if (!compact) {
            val handle = "@${stats.userName}"
            val handlePaint = paint(600, colors.secondary, if (settings.style == WidgetStyle.MATERIAL) 14f else 12f, 51, 100)
            val footerLogoSize = context.resources.getDimension(R.dimen.widget_footer_logo_size)
            shrinkToFit(handlePaint, handle, width - footerLogoSize - 34f * density)
            val handleGap = 6f * density
            val handleCenterY = top + 41f * density
            val handleBaseline = handleCenterY - (handlePaint.fontMetrics.ascent + handlePaint.fontMetrics.descent) / 2f
            val handleLeft = (width - footerLogoSize - handleGap - handlePaint.measureText(handle)) / 2f
            drawLogo(context, canvas, settings, colors.secondary, handleLeft, handleCenterY - footerLogoSize / 2f, footerLogoSize)
            canvas.drawText(handle, handleLeft + footerLogoSize + handleGap, handleBaseline, handlePaint)
        }
        return bitmap
    }

    private fun drawLogo(context: Context, canvas: Canvas, settings: TwidgetWidgetSettings, color: Int, left: Float, top: Float, size: Float) {
        ContextCompat.getDrawable(context, if (settings.logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x)
            ?.mutate()?.apply {
                setTint(color)
                setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
                draw(canvas)
            }
    }

    internal fun drawWidgetBackground(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int,
        settings: TwidgetWidgetSettings,
        dark: Boolean,
    ) {
        val radius = if (settings.style == WidgetStyle.ONE_UI && height / context.resources.displayMetrics.density <= 110f)
            height / 2f else dp(context, 26).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetColors.resolve(context, settings, dark).background
        }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
    }

    private fun shrinkToFit(paint: Paint, text: String, maxWidth: Float) {
        if (maxWidth <= 0f) return
        while (paint.measureText(text) > maxWidth && paint.textSize > 8f) {
            paint.textSize -= 1f
        }
    }

    private fun withAlpha(color: Int, fraction: Float): Int =
        Color.argb((255 * fraction).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun gsfTypeface(context: Context) =
        TwidgetFonts.googleSansFlex(context)

    // Use the bundled variable face rather than Samsung's system aliases. Some
    // One UI releases map their nominal Bold face closer to ExtraBold.
    private fun oneUiTypeface(context: Context) =
        TwidgetFonts.oneUiSansVariable(context)

    private fun textPaint(context: Context, settings: TwidgetWidgetSettings, color: Int, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            applyWidgetTypeface(context, settings.fontFamily, if (bold) 700 else 400)
        }

    private fun Paint.applyWidgetTypeface(
        context: Context,
        fontFamily: String,
        weight: Int,
        googleWidth: Int? = null,
        googleRoundness: Int = 0,
        googleSlant: Int = 0,
    ) {
        val flex = fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX
        val key = FontStyle(fontFamily, weight, if (flex) (googleWidth ?: 100).coerceIn(25, 151) else 100,
            if (flex) googleRoundness else 0, if (flex) googleSlant else 0)
        val oldColor = color
        val oldSize = textSize
        synchronized(fontPaints) {
            val template = fontPaints[key] ?: Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                when (fontFamily) {
                    TwidgetStore.FONT_SYSTEM -> typeface = TwidgetFonts.system(weight)
                    TwidgetStore.FONT_GOOGLE_SANS_FLEX -> {
                        fontFeatureSettings = "'dlig' 1, 'lnum' 1, 'pnum' 1"
                        typeface = gsfTypeface(context)
                        setFontVariationSettings(
                            "'wght' $weight, 'wdth' ${key.width}, 'ROND' ${key.roundness}, 'slnt' ${key.slant}, 'GRAD' 0, 'opsz' 18",
                        )
                    }
                    else -> {
                        typeface = oneUiTypeface(context)
                        setFontVariationSettings("'wght' $weight")
                    }
                }
            }.also { fontPaints.put(key, it) }
            set(template)
        }
        color = oldColor
        textSize = oldSize
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
