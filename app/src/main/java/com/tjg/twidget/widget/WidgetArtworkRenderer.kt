package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.core.content.ContextCompat
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.ui.TwidgetFonts

object WidgetArtworkRenderer {
    internal const val ONE_UI_EMPHASIS_WEIGHT = 700

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
    ): Bitmap {
        if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_2X1 || mode == TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP) {
            return renderCompact(context, widthPx, heightPx, stats, settings, mode, dark, delta, drawBackground)
        }
        val width = widthPx.coerceAtLeast(dp(context, 120))
        val height = heightPx.coerceAtLeast(dp(context, 120))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawBackground) drawWidgetBackground(context, canvas, width, height, settings, dark)
        val density = context.resources.displayMetrics.density
        val colors = WidgetColors.resolve(context, settings, dark)
        val primary = colors.primary
        val secondary = colors.secondary
        val footerPaint = textPaint(context, settings, primary, bold = true).apply {
            textSize = 14f * density
            applyWidgetTypeface(context, settings.fontFamily, 600, 51, 100)
        }
        val deltaText = if (!settings.showDelta || delta == 0L) "" else TwidgetStore.signedNumber(delta, AppLocales.resolve(settings.language))
        val deltaPaint = textPaint(
            context,
            settings,
            if (delta < 0) Color.rgb(229, 57, 53) else Color.rgb(46, 125, 50),
            bold = true,
        ).apply {
            textSize = if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE) 12f * density else 14f * density
        }

        val pad = 10f * density
        val footerHeight = 20f * density
        val textMaxWidth = width - pad * 2
        val textMaxHeight = height - pad * 2 - footerHeight
        val locale = AppLocales.resolve(settings.language)
        val localizedContext = AppLocales.wrap(context, settings.language)
        val words = TwidgetWidget.followersInWords(stats.followersCount, locale)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() } + localizedContext.getString(R.string.followers)
        // Axis setup constructs a native variable typeface. Keep one paint per
        // distinct word throughout fitting and drawing instead of rebuilding it
        // hundreds of times for every resize. Paints remain local to this render.
        val wordPaints = words.distinct().associateWith { wordPaint(context, settings, it, primary, secondary) }
        val gap = wordSpacing(context, textMaxWidth)
        val textSize = findTextSize(words, wordPaints, textMaxWidth, textMaxHeight, gap)
        val lines = wrapWords(words, wordPaints, textMaxWidth, textSize, gap)
        val lineHeight = textSize + gap
        val top = pad + textSize * 0.8f

        lines.forEachIndexed { lineIndex, line ->
            var x = pad
            val y = top + lineIndex * lineHeight
            line.forEach { word ->
                val paint = wordPaints.getValue(word).apply {
                    this.textSize = textSize
                }
                canvas.drawText(word, x, y, paint)
                x += paint.measureText(word) + gap
            }
        }

        val handle = "@${stats.userName}"
        val footerY = height - pad - 4f * density
        val logoSize = 20f * density
        val logo = ContextCompat.getDrawable(
            context,
            if (settings.logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x,
        )?.mutate()?.apply { setTint(primary) }
        val logoCenterY = height - pad - logoSize / 2f
        logo?.setBounds(
            pad.toInt(),
            (logoCenterY - logoSize / 2f).toInt(),
            (pad + logoSize).toInt(),
            (logoCenterY + logoSize / 2f).toInt(),
        )
        logo?.draw(canvas)
        val handleX = pad + logoSize + 6f * density
        val deltaWidth = if (deltaText.isEmpty()) 0f else deltaPaint.measureText(deltaText)
        val handleMaxWidth = width - handleX - pad - deltaWidth - if (deltaText.isEmpty()) 0f else 10f * density
        shrinkToFit(footerPaint, handle, handleMaxWidth)
        canvas.drawText(handle, handleX, footerY, footerPaint.apply { color = secondary })
        if (deltaText.isNotEmpty()) {
            canvas.drawText(deltaText, width - pad - deltaPaint.measureText(deltaText), footerY, deltaPaint)
        }
        return bitmap
    }

    private fun findTextSize(
        words: List<String>,
        paints: Map<String, Paint>,
        maxWidth: Float,
        maxHeight: Float,
        gap: Float,
    ): Float {
        // Find the largest size that fits the host's actual rectangle. Fixed
        // 20/32sp caps left a large unused band on wider/resized widgets.
        var low = 1f
        var high = maxHeight.coerceAtLeast(low)
        repeat(14) {
            val size = (low + high) / 2f
            val lines = wrapWords(words, paints, maxWidth, size, gap)
            val fits = words.all { measureWord(paints.getValue(it), it, size) <= maxWidth } &&
                lines.size * size + (lines.size - 1) * gap <= maxHeight
            if (fits) low = size else high = size
        }
        return low
    }

    private fun wordSpacing(context: Context, maxWidth: Float): Float =
        (if (maxWidth / context.resources.displayMetrics.density < 230f) 4f else 6f) * context.resources.displayMetrics.density

    private fun measureWord(paint: Paint, word: String, textSize: Float): Float =
        paint.apply { this.textSize = textSize }.measureText(word)

    private fun wrapWords(
        words: List<String>,
        paints: Map<String, Paint>,
        maxWidth: Float,
        textSize: Float,
        space: Float,
    ): List<List<String>> {
        // Measure each word with the paint it will actually be drawn with —
        // per-word weight/width means a single measuring paint would misjudge
        // the heavier emphasis words and overflow the card.
        val lines = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentWidth = 0f

        words.forEach { word ->
            val width = measureWord(paints.getValue(word), word, textSize)
            if (current.isNotEmpty() && currentWidth + space + width > maxWidth) {
                lines += current
                current = mutableListOf()
                currentWidth = 0f
            }
            current += word
            currentWidth += if (currentWidth == 0f) width else space + width
        }
        if (current.isNotEmpty()) lines += current
        return lines
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
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
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
        val deltaPaint = paint(400, if (delta < 0) Color.rgb(255, 59, 48) else
            if (settings.style == WidgetStyle.MATERIAL) Color.rgb(12, 162, 86) else Color.rgb(46, 125, 50), 26f, 57, 100)
        val compact = mode == TwidgetWidget.LAYOUT_MODE_COMPACT_2X1
        val gap = (if (compact) 6f else 10f) * density
        val logoSize = (if (compact) 26f else 18f) * density
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
        if (lineWidth() > width - 18f * density) {
            val scale = ((width - 18f * density) / lineWidth()).coerceIn(0.1f, 1f)
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
            val handlePaint = paint(600, colors.secondary, 14f, 51, 100)
            shrinkToFit(handlePaint, handle, width - logoSize - 34f * density)
            val handleGap = 6f * density
            val handleLeft = (width - logoSize - handleGap - handlePaint.measureText(handle)) / 2f
            val handleTop = top + 32f * density
            drawLogo(context, canvas, settings, colors.secondary, handleLeft, handleTop, logoSize)
            canvas.drawText(handle, handleLeft + logoSize + handleGap,
                handleTop + logoSize / 2f - (handlePaint.fontMetrics.ascent + handlePaint.fontMetrics.descent) / 2f, handlePaint)
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
        val radius = if (settings.style == WidgetStyle.MATERIAL) dp(context, 26).toFloat() else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.resources.getDimension(android.R.dimen.system_app_widget_background_radius)
        } else {
            dp(context, 24).toFloat()
        }
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

    // Word classes for the spelled-out follower count. Each maps to a
    // typographic role that both fonts render in their own register (see the
    // Figma 4x2 widget spec).
    private val ONES_WORDS = setOf(
        "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen",
        "Ein", "Eine", "Eins", "Zwei", "Drei", "Vier", "Fünf", "Sechs", "Sieben", "Acht", "Neun",
        "Zehn", "Elf", "Zwölf", "Dreizehn", "Vierzehn", "Fünfzehn", "Sechzehn",
        "Siebzehn", "Achtzehn", "Neunzehn", "Null",
    )
    private val TENS_WORDS = setOf(
        "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety",
        "Zwanzig", "Dreißig", "Vierzig", "Fünfzig", "Sechzig", "Siebzig", "Achtzig", "Neunzig",
    )
    private val SCALE_WORDS = setOf(
        "Thousand", "Million", "Billion", "Trillion", "Quadrillion", "Quintillion",
        "Tausend", "Millionen", "Milliarde", "Milliarden", "Billionen", "Billiarde", "Billiarden",
        "Trillionen",
    )
    private val CONNECTOR_WORDS = setOf("Hundred", "and", "Hundert", "und")

    // Typographic role per word. TENS carries the loudest emphasis, ONES next,
    // HUNDRED anchors the scale, SOFT words (thousand/million/"and") recede, and
    // LABEL ("Followers") is the quietest. Emphasis is by weight, hierarchy by
    // opacity — and for the variable font, by width too.
    private enum class WordRole { TENS, ONES, HUNDRED, SOFT, LABEL, STRONG }

    private fun roleOf(context: Context, word: String): WordRole {
        val isLabel = word.equals("Follower", ignoreCase = true) ||
            word.equals("Followers", ignoreCase = true) ||
            word.equals(context.getString(R.string.followers), ignoreCase = true)
        if (isLabel) return WordRole.LABEL
        return when (val bare = word.trim(',')) {
            in TENS_WORDS -> WordRole.TENS
            in ONES_WORDS -> WordRole.ONES
            in SCALE_WORDS -> WordRole.SOFT
            in CONNECTOR_WORDS ->
                if (bare.equals("Hundred", ignoreCase = true) || bare.equals("Hundert", ignoreCase = true)) {
                    WordRole.HUNDRED
                } else {
                    WordRole.SOFT
                }
            else -> WordRole.STRONG
        }
    }

    // Per-role weights, kept separate for the two families. One UI Sans keeps
    // its emphasized magnitude words at Bold rather than ExtraBold, while
    // Google Sans Flex has a true Black and peaks only on the tens word.
    private fun oneUiWeightFor(role: WordRole): Int = when (role) {
        WordRole.TENS, WordRole.ONES -> ONE_UI_EMPHASIS_WEIGHT
        WordRole.HUNDRED -> 600
        WordRole.STRONG -> 700
        WordRole.SOFT -> 400
        WordRole.LABEL -> 200
    }

    private fun gsfWeightFor(role: WordRole): Int = when (role) {
        WordRole.TENS -> 900
        WordRole.ONES, WordRole.STRONG -> 900
        WordRole.HUNDRED, WordRole.SOFT -> 600
        WordRole.LABEL -> 400
    }

    private fun gsfWidthFor(role: WordRole): Int = when (role) {
        WordRole.TENS -> 65
        WordRole.ONES, WordRole.STRONG -> 118
        WordRole.HUNDRED, WordRole.SOFT -> 100
        WordRole.LABEL -> 69
    }

    private fun wordPaint(
        context: Context,
        settings: TwidgetWidgetSettings,
        word: String,
        primary: Int,
        @Suppress("UNUSED_PARAMETER") secondary: Int,
    ): Paint {
        val role = roleOf(context, word)
        val gsf = settings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX
        val weight = if (gsf) gsfWeightFor(role) else oneUiWeightFor(role)
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            // Label opacity (0.6) comes straight from the design.
            color = if (role == WordRole.LABEL) {
                if (settings.style == WidgetStyle.MATERIAL) secondary else withAlpha(primary, 0.6f)
            } else primary
            applyWidgetTypeface(
                context = context,
                fontFamily = settings.fontFamily,
                weight = weight,
                googleWidth = if (word.equals("and", true) || word.equals("und", true)) 78 else gsfWidthFor(role),
                googleSlant = if (word.trim(',') in SCALE_WORDS) -10 else 0,
            )
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
        when (fontFamily) {
            TwidgetStore.FONT_SYSTEM -> typeface = TwidgetFonts.system(weight)
            TwidgetStore.FONT_GOOGLE_SANS_FLEX -> {
                fontFeatureSettings = "'dlig' 1, 'lnum' 1, 'pnum' 1"
                typeface = gsfTypeface(context)
                setFontVariationSettings(
                    "'wght' $weight, 'wdth' ${googleWidth ?: 100}, 'ROND' $googleRoundness, 'slnt' $googleSlant, 'GRAD' 0, 'opsz' 18",
                )
            }
            else -> {
                typeface = oneUiTypeface(context)
                setFontVariationSettings("'wght' $weight")
            }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
