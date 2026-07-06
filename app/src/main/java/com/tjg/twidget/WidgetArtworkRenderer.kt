package com.tjg.twidget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min

object WidgetArtworkRenderer {
    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        stats: ProfileStats,
        settings: TwidgetWidgetSettings,
        mode: Int,
        dark: Boolean,
        delta: Long = 0,
    ): Bitmap {
        if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_2X1 || mode == TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP) {
            return renderCompact(context, widthPx, heightPx, stats, settings, mode, dark, delta)
        }
        val width = widthPx.coerceAtLeast(dp(context, 120))
        val height = heightPx.coerceAtLeast(dp(context, 120))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        val primary = if (dark) Color.WHITE else Color.BLACK
        val secondary = Color.argb(204, Color.red(primary), Color.green(primary), Color.blue(primary))
        val footerPaint = textPaint(context, settings, primary, bold = true).apply {
            textSize = 12f * density
        }
        val deltaText = if (!settings.showDelta || delta == 0L) "" else TwidgetStore.signedNumber(delta)
        val deltaPaint = textPaint(
            context,
            settings,
            if (delta < 0) Color.rgb(229, 57, 53) else Color.rgb(46, 125, 50),
            bold = true,
        ).apply {
            textSize = if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE) 12f * density else 14f * density
        }

        val pad = 10f * density
        val footerHeight = if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE) 20f * density else 26f * density
        val textMaxWidth = width - pad * 2
        val textMaxHeight = height - pad * 2 - footerHeight
        val words = TwidgetWidget.followersInWords(stats.followersCount)
            .split(" ")
            .filter { it.isNotBlank() } + context.getString(R.string.followers)
        val textSize = findTextSize(context, settings, words, textMaxWidth, textMaxHeight)
        val lines = wrapWords(context, settings, words, textMaxWidth, textSize)
        val lineHeight = textSize * 1.12f
        val top = pad + max(0f, (textMaxHeight - lines.size * lineHeight) / 2f) + textSize * 0.88f

        lines.forEachIndexed { lineIndex, line ->
            var x = pad
            val y = top + lineIndex * lineHeight
            line.forEach { word ->
                val paint = wordPaint(context, settings, word, primary, secondary).apply {
                    this.textSize = textSize
                }
                canvas.drawText(word, x, y, paint)
                x += paint.measureText(word) + 6f * density
            }
        }

        val handle = "@${stats.userName}"
        val footerY = height - pad - 4f * density
        val logoSize = 13f * density
        val logo = ContextCompat.getDrawable(
            context,
            if (settings.logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x,
        )?.mutate()?.apply { setTint(primary) }
        val logoCenterY = footerY - 4.5f * density
        logo?.setBounds(
            pad.toInt(),
            (logoCenterY - logoSize / 2f).toInt(),
            (pad + logoSize).toInt(),
            (logoCenterY + logoSize / 2f).toInt(),
        )
        logo?.draw(canvas)
        val handleX = pad + logoSize + 8f * density
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
        context: Context,
        settings: TwidgetWidgetSettings,
        words: List<String>,
        maxWidth: Float,
        maxHeight: Float,
    ): Float {
        var size = 42f * context.resources.displayMetrics.scaledDensity
        val min = 15f * context.resources.displayMetrics.scaledDensity
        while (size > min) {
            val lines = wrapWords(context, settings, words, maxWidth, size)
            if (lines.size * size * 1.12f <= maxHeight) return size
            size -= 1f * context.resources.displayMetrics.scaledDensity
        }
        return min
    }

    private fun wrapWords(
        context: Context,
        settings: TwidgetWidgetSettings,
        words: List<String>,
        maxWidth: Float,
        textSize: Float,
    ): List<List<String>> {
        val paint = textPaint(context, settings, Color.BLACK, bold = true).apply { this.textSize = textSize }
        val lines = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var currentWidth = 0f
        val space = paint.measureText(" ")
        words.forEach { word ->
            val width = paint.measureText(word)
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
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val primary = if (dark) Color.WHITE else Color.BLACK
        val value = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(stats.followersCount)
        val label = context.getString(R.string.followers)

        fun paintFor(weight: Int, color: Int, sizeSp: Float) =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.color = color
                textSize = sizeSp * density
                typeface = if (settings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX) {
                    gsfTypeface(context, weight)
                } else {
                    Typeface.create("sec", if (weight >= 700) Typeface.BOLD else Typeface.NORMAL)
                }
            }

        if (mode == TwidgetWidget.LAYOUT_MODE_COMPACT_2X1) {
            val valuePaint = paintFor(700, primary, 24f)
            val labelPaint = paintFor(700, primary, 16f)
            val deltaText = if (!settings.showDelta || delta == 0L) "" else TwidgetStore.signedNumber(delta)
            val deltaPaint = paintFor(700, if (delta < 0) Color.rgb(229, 57, 53) else Color.rgb(46, 125, 50), 13f)
            val lineGap = 7f * density
            var valueLineWidth = valuePaint.measureText(value) +
                if (deltaText.isEmpty()) 0f else lineGap + deltaPaint.measureText(deltaText)
            if (valueLineWidth > width - 8f * density) {
                val scale = (width - 8f * density) / valueLineWidth
                valuePaint.textSize *= scale
                deltaPaint.textSize *= scale
                valueLineWidth = valuePaint.measureText(value) +
                    if (deltaText.isEmpty()) 0f else lineGap + deltaPaint.measureText(deltaText)
            }
            shrinkToFit(labelPaint, label, width - 8f * density)
            val gap = 4f * density
            val valueHeight = max(valuePaint.textSize, deltaPaint.textSize)
            val labelHeight = labelPaint.textSize
            val blockTop = (height - valueHeight - gap - labelHeight) / 2f
            var x = (width - valueLineWidth) / 2f
            val valueBaseline = blockTop + valueHeight * 0.9f
            canvas.drawText(value, x, valueBaseline, valuePaint)
            if (deltaText.isNotEmpty()) {
                x += valuePaint.measureText(value) + lineGap
                canvas.drawText(deltaText, x, valueBaseline - (valuePaint.textSize - deltaPaint.textSize) * 0.2f, deltaPaint)
            }
            canvas.drawText(label, (width - labelPaint.measureText(label)) / 2f, blockTop + valueHeight + gap + labelHeight * 0.9f, labelPaint)
        } else {
            val valuePaint = paintFor(700, primary, 22f)
            val labelPaint = paintFor(400, primary, 21f)
            val deltaPaint = paintFor(400, if (delta < 0) Color.rgb(229, 57, 53) else Color.rgb(46, 125, 50), 18f)
            val handlePaint = paintFor(700, primary, 12f)
            val deltaText = if (!settings.showDelta || delta == 0L) "" else TwidgetStore.signedNumber(delta)
            val wordGap = 8f * density

            var lineWidth = valuePaint.measureText(value) + wordGap + labelPaint.measureText(label)
            if (deltaText.isNotEmpty()) lineWidth += wordGap + deltaPaint.measureText(deltaText)
            if (lineWidth > width - 12f * density) {
                val scale = (width - 12f * density) / lineWidth
                listOf(valuePaint, labelPaint, deltaPaint).forEach { it.textSize *= scale }
                lineWidth = valuePaint.measureText(value) + wordGap + labelPaint.measureText(label) +
                    if (deltaText.isEmpty()) 0f else wordGap + deltaPaint.measureText(deltaText)
            }

            val logoSize = 14f * density
            val handleGap = 6f * density
            val line2Height = 14f * density
            val line1Height = valuePaint.textSize
            val blockTop = (height - line1Height - 6f * density - line2Height) / 2f

            var x = (width - lineWidth) / 2f
            val line1Baseline = blockTop + line1Height * 0.9f
            canvas.drawText(value, x, line1Baseline, valuePaint)
            x += valuePaint.measureText(value) + wordGap
            canvas.drawText(label, x, line1Baseline, labelPaint)
            if (deltaText.isNotEmpty()) {
                x += labelPaint.measureText(label) + wordGap
                canvas.drawText(deltaText, x, line1Baseline, deltaPaint)
            }

            val handle = "@${stats.userName}"
            val line2Width = logoSize + handleGap + handlePaint.measureText(handle)
            val line2Top = blockTop + line1Height + 6f * density
            var x2 = (width - line2Width) / 2f
            val logo = ContextCompat.getDrawable(
                context,
                if (settings.logo == TwidgetStore.LOGO_TWITTER) R.drawable.ic_logo_twitter else R.drawable.ic_logo_x,
            )?.mutate()?.apply { setTint(primary) }
            logo?.setBounds(x2.toInt(), line2Top.toInt(), (x2 + logoSize).toInt(), (line2Top + logoSize).toInt())
            logo?.draw(canvas)
            x2 += logoSize + handleGap
            canvas.drawText(handle, x2, line2Top + logoSize * 0.82f, handlePaint)
        }
        return bitmap
    }

    private fun shrinkToFit(paint: Paint, text: String, maxWidth: Float) {
        if (maxWidth <= 0f) return
        while (paint.measureText(text) > maxWidth && paint.textSize > 8f) {
            paint.textSize -= 1f
        }
    }

    // Word classes taken from the Figma 2x2 widget spec: number words Bold(700),
    // tens Black(900), scale/connector words SemiBold(600) with the scale word
    // slanted, "Followers" Regular(400) at 80% opacity.
    private val ONES_WORDS = setOf(
        "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen",
    )
    private val TENS_WORDS = setOf("Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")
    private val SCALE_WORDS = setOf("Thousand", "Million")
    private val CONNECTOR_WORDS = setOf("Hundred", "and")

    private fun wordPaint(
        context: Context,
        settings: TwidgetWidgetSettings,
        word: String,
        primary: Int,
        secondary: Int,
    ): Paint {
        val isFollowers = word.equals(context.getString(R.string.followers), ignoreCase = true)
        if (settings.fontFamily != TwidgetStore.FONT_GOOGLE_SANS_FLEX) {
            return textPaint(context, settings, if (isFollowers) secondary else primary, bold = !isFollowers)
        }
        val bare = word.trim(',')
        val weight = when {
            isFollowers -> 400
            bare in TENS_WORDS -> 900
            bare in SCALE_WORDS || bare in CONNECTOR_WORDS -> 600
            else -> 700 // ones/teens and plain numerals
        }
        val slanted = bare in SCALE_WORDS
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = if (isFollowers) secondary else primary
            typeface = gsfTypeface(context, weight)
            // No-op on the bundled static cuts; picks up real axes if a variable
            // Google Sans Flex is dropped into res/font.
            setFontVariationSettings(buildString {
                append("'wght' $weight")
                if (isFollowers) append(", 'wdth' 85")
                if (slanted) append(", 'slnt' -8")
            })
            if (slanted) textSkewX = -0.08f
        }
    }

    private val gsfWeightCache = mutableMapOf<Int, Typeface>()

    private fun gsfTypeface(context: Context, weight: Int): Typeface =
        gsfWeightCache.getOrPut(weight) {
            val base = ResourcesCompat.getFont(
                context,
                if (weight >= 700) R.font.google_sans_flex_bold else R.font.google_sans_flex_regular,
            ) ?: Typeface.DEFAULT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight, false) else base
        }

    private fun textPaint(context: Context, settings: TwidgetWidgetSettings, color: Int, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            typeface = if (settings.fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX) {
                ResourcesCompat.getFont(
                    context,
                    if (bold) R.font.google_sans_flex_bold else R.font.google_sans_flex_regular,
                )
            } else {
                Typeface.create("sec", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
