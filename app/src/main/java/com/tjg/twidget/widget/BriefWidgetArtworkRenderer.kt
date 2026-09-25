package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.MetricAffectingSpan
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCard
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefEditorialSummary
import com.tjg.twidget.brief.BriefStrings
import com.tjg.twidget.brief.BriefSnapshot
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.R as OneUiIconR

/** Transparent, launcher-safe artwork for the responsive Brief widget. */
internal object BriefWidgetArtworkRenderer {
    enum class Layout { COMPACT_STRIP, WIDE_STRIP, SQUARE, MEDIUM_TALL, WIDE_TALL }

    internal data class TallCardMetrics(
        val iconInsetDp: Float,
        val textInsetDp: Float,
        val bottomInsetDp: Float,
        val iconSizeDp: Float,
        val titleSizeSp: Float,
        val bodySizeSp: Float,
        val textGapDp: Float,
        val titleWeight: Int,
    )

    fun layout(widthDp: Float, heightDp: Float): Layout = when {
        heightDp <= 110f && widthDp <= 230f -> Layout.COMPACT_STRIP
        heightDp <= 110f -> Layout.WIDE_STRIP
        widthDp <= 230f -> Layout.SQUARE
        widthDp < 300f -> Layout.MEDIUM_TALL
        else -> Layout.WIDE_TALL
    }

    /** Proportions used by Samsung's Now Brief medium/tall widget. */
    internal fun tallCardMetrics(widthDp: Float, heightDp: Float): TallCardMetrics {
        val compactWidth = widthDp < 300f
        return TallCardMetrics(
            iconInsetDp = if (widthDp <= 230f) 10f else 14f,
            textInsetDp = if (widthDp <= 230f) 10f else 14f,
            bottomInsetDp = if (widthDp <= 230f) 10f else 14f,
            iconSizeDp = 48f,
            titleSizeSp = if (widthDp <= 230f) 18f else if (compactWidth) 22f else 26f,
            bodySizeSp = if (compactWidth) 12f else 16f,
            textGapDp = 5f,
            titleWeight = 700,
        )
    }

    @DrawableRes
    fun supportingIcon(type: BriefCardType): Int = when (type) {
        BriefCardType.SUMMARY -> R.drawable.ic_twidget_notification
        BriefCardType.GROWTH -> R.drawable.ic_import_analytics
        BriefCardType.SLOWDOWN -> OneUiIconR.drawable.ic_oui_time_outline
        BriefCardType.INACTIVITY -> OneUiIconR.drawable.ic_oui_compose_edit
        BriefCardType.MILESTONE -> R.drawable.ic_milestone_goals
        BriefCardType.POST, BriefCardType.WORST_POST -> OneUiIconR.drawable.ic_oui_equalizer_2
        BriefCardType.TOP_FOLLOWER -> OneUiIconR.drawable.ic_oui_community
        BriefCardType.STREAK -> R.drawable.ic_streak_fire
        BriefCardType.SCHEDULE_GUIDE -> OneUiIconR.drawable.ic_oui_calendar_task
        BriefCardType.POST_FOLLOW_THROUGH -> OneUiIconR.drawable.ic_oui_repeat
        BriefCardType.POSTING_GUIDE -> OneUiIconR.drawable.ic_oui_star_outline
    }

    fun render(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        account: String,
        snapshot: BriefSnapshot?,
        dark: Boolean,
        fontFamily: String = TwidgetStore.FONT_ONE_UI_SANS,
        strings: BriefStrings = BriefStrings.from(context),
        style: WidgetStyle = WidgetStyle.ONE_UI,
        background: Int? = null,
        useProfileImages: Boolean = true,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        background?.let { color ->
            val radius = dp(context, if (style == WidgetStyle.MATERIAL || height / density > 110f) 26f else height / density / 2f)
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        val card = snapshot?.cards?.firstOrNull() ?: BriefCard(
            id = "empty",
            type = BriefCardType.SUMMARY,
            title = context.getString(R.string.brief_widget_empty_title),
            body = context.getString(R.string.brief_widget_empty_body),
            score = 0,
        )
        val summary = snapshot?.let { BriefEditorialSummary.from(it, strings) }
        val displayCard = card.copy(
            title = summary?.title ?: card.title,
            body = summary?.shortDescription ?: card.body,
        )
        val widgetLayout = layout(width / density, height / density)
        val colors = WidgetColors.resolve(context, style, dark)
        val primary = colors.primary
        val secondary = if (style == WidgetStyle.MATERIAL) colors.secondary else primary

        when (widgetLayout) {
            Layout.COMPACT_STRIP -> drawCenteredTitle(
                context = context,
                canvas = canvas,
                title = displayCard.title,
                left = dp(context, 14).toFloat(),
                width = width - dp(context, 28).toFloat(),
                height = height,
                sizeSp = 16f,
                maxLines = 2,
                color = primary,
                fontFamily = fontFamily,
            )
            Layout.WIDE_STRIP -> {
                val pad = dp(context, 14).toFloat()
                val iconSize = minOf(dp(context, 48).toFloat(), height - pad * 2f)
                drawStateIcon(
                    context,
                    canvas,
                    displayCard.type,
                    account,
                    pad,
                    (height - iconSize) / 2f,
                    iconSize,
                    primary,
                    useProfileImages,
                )
                val textLeft = pad + iconSize + dp(context, 14)
                drawCenteredTitle(
                    context = context,
                    canvas = canvas,
                    title = displayCard.title,
                    left = textLeft,
                    width = width - textLeft - pad,
                    height = height,
                    sizeSp = 24f,
                    maxLines = 2,
                    color = primary,
                    fontFamily = fontFamily,
                )
            }
            Layout.SQUARE, Layout.MEDIUM_TALL, Layout.WIDE_TALL -> {
                val metrics = tallCardMetrics(width / density, height / density)
                drawTallCard(
                    context, canvas, displayCard, account, width, height, primary, secondary,
                    paddingDp = metrics.textInsetDp,
                    iconSizeDp = metrics.iconSizeDp,
                    titleSizeSp = metrics.titleSizeSp,
                    bodySizeSp = metrics.bodySizeSp,
                    titleLines = 2,
                    bodyLines = if (width / density <= 230f) 3 else 2,
                    iconStartDp = metrics.iconInsetDp,
                    iconTopDp = metrics.iconInsetDp,
                    bottomPaddingDp = metrics.bottomInsetDp,
                    gapDp = metrics.textGapDp,
                    titleWeight = metrics.titleWeight,
                    fontFamily = fontFamily,
                    useProfileImages = useProfileImages,
                )
            }
        }
        return bitmap
    }

    private fun drawCenteredTitle(
        context: Context,
        canvas: Canvas,
        title: String,
        left: Float,
        width: Float,
        height: Int,
        sizeSp: Float,
        maxLines: Int,
        color: Int,
        fontFamily: String,
    ) {
        val paint = textPaint(context, fontFamily, 700, sizeSp, color)
        var lines = wrap(title, paint, width, maxLines)
        // Keep a wrapped headline inside short widgets at larger system font scales.
        val availableHeight = height - dp(context, 12)
        repeat(8) {
            val metrics = paint.fontMetrics
            val blockHeight = metrics.bottom - metrics.top + (lines.size - 1) * paint.textSize * 1.13f
            if (blockHeight > availableHeight) {
                paint.textSize *= availableHeight / blockHeight
                lines = wrap(title, paint, width, maxLines)
            }
        }
        val lineHeight = paint.textSize * 1.13f
        val metrics = paint.fontMetrics
        val firstBaseline = centeredFirstBaseline(
            height = height.toFloat(),
            fontTop = metrics.top,
            fontBottom = metrics.bottom,
            lineHeight = lineHeight,
            lineCount = lines.size,
        )
        lines.forEachIndexed { index, line ->
            val shown = ellipsize(line, paint, width)
            canvas.drawText(
                shown,
                left + (width - paint.measureText(shown)) / 2f,
                firstBaseline + index * lineHeight,
                paint,
            )
        }
    }

    internal fun centeredFirstBaseline(
        height: Float,
        fontTop: Float,
        fontBottom: Float,
        lineHeight: Float,
        lineCount: Int,
    ): Float {
        val additionalLinesHeight = (lineCount.coerceAtLeast(1) - 1) * lineHeight
        val blockHeight = fontBottom - fontTop + additionalLinesHeight
        return (height - blockHeight) / 2f - fontTop
    }

    private fun drawTallCard(
        context: Context,
        canvas: Canvas,
        card: BriefCard,
        account: String,
        widthPx: Int,
        heightPx: Int,
        primary: Int,
        secondary: Int,
        paddingDp: Float,
        iconSizeDp: Float,
        titleSizeSp: Float,
        bodySizeSp: Float,
        titleLines: Int,
        bodyLines: Int,
        iconStartDp: Float = paddingDp,
        iconTopDp: Float = paddingDp,
        bottomPaddingDp: Float = paddingDp,
        gapDp: Float = 5f,
        titleWeight: Int = 700,
        fontFamily: String,
        useProfileImages: Boolean,
    ) {
        val pad = dp(context, paddingDp)
        val iconStart = dp(context, iconStartDp)
        val iconTop = dp(context, iconTopDp)
        val bottomPad = dp(context, bottomPaddingDp)
        val iconSize = minOf(dp(context, iconSizeDp), heightPx - iconTop - bottomPad)
        drawStateIcon(context, canvas, card.type, account, iconStart, iconTop, iconSize, primary, useProfileImages)

        val textWidth = (widthPx - pad * 2f).toInt().coerceAtLeast(1)
        val gap = dp(context, gapDp)
        val availableHeight = (heightPx - bottomPad - iconTop - iconSize - gap).coerceAtLeast(1f)
        var scale = 1f
        var titleLayout: StaticLayout
        var bodyLayout: StaticLayout
        do {
            val titlePaint = TextPaint(textPaint(context, fontFamily, titleWeight, titleSizeSp * scale, primary))
            val bodyPaint = TextPaint(textPaint(context, fontFamily, 400, bodySizeSp * scale, secondary))
            titleLayout = textLayout(card.title, titlePaint, textWidth, titleLines)
            bodyLayout = textLayout(emphasizedBody(context, card.body, fontFamily), bodyPaint, textWidth, bodyLines)
            if (titleLayout.height + gap + bodyLayout.height <= availableHeight || scale <= .6f) break
            scale -= .05f
        } while (true)
        val blockTop = maxOf(iconTop + iconSize + gap,
            heightPx - bottomPad - titleLayout.height - gap - bodyLayout.height)
        canvas.save()
        canvas.translate(pad, blockTop)
        titleLayout.draw(canvas)
        canvas.translate(0f, titleLayout.height + gap)
        bodyLayout.draw(canvas)
        canvas.restore()
    }

    private fun textLayout(text: CharSequence, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** Figma emphasizes account handles and quantities, but leaves follower ranks in body weight. */
    internal fun emphasisRanges(text: String): List<IntRange> =
        Regex("@[\\p{L}\\p{N}_]+|(?<![#\\p{L}\\p{N}])\\p{N}+(?:[,.\\u00a0]\\p{N}+)*(?:[KMBkmb%])?")
            .findAll(text).map { it.range }.toList()

    private fun emphasizedBody(context: Context, text: String, fontFamily: String): CharSequence =
        SpannableString(text).apply {
            emphasisRanges(text).forEach { range ->
                setSpan(object : MetricAffectingSpan() {
                    override fun updateMeasureState(paint: TextPaint) = applyEmphasis(paint)
                    override fun updateDrawState(paint: TextPaint) = applyEmphasis(paint)
                    private fun applyEmphasis(paint: TextPaint) {
                        val bold = textPaint(context, fontFamily, 700, 12f, paint.color)
                        paint.typeface = bold.typeface
                        paint.fontVariationSettings = bold.fontVariationSettings
                    }
                }, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

    private fun drawStateIcon(
        context: Context,
        canvas: Canvas,
        type: BriefCardType,
        account: String,
        left: Float,
        top: Float,
        size: Float,
        tint: Int,
        useProfileImages: Boolean,
    ) {
        if (type == BriefCardType.SLOWDOWN) {
            // Separate Figma arc layers preserve transparent space around the glyph.
            val inset = size * (11.368421f / 48f)
            drawDialLayer(context, canvas, R.drawable.widget_brief_dial_track, left, top, size,
                1.38525390625f, 1.380859375f, 45.2392578125f, 40.72216796875f)
            drawDialLayer(context, canvas, R.drawable.widget_brief_dial_progress, left, top, size,
                1.385009765625f, 1.380859375f, 36.1416015625f, 40.72998046875f)
            ContextCompat.getDrawable(context, R.drawable.widget_brief_goal)?.mutate()?.apply {
                setTint(tint)
                setBounds((left + inset).toInt(), (top + inset).toInt(), (left + size - inset).toInt(), (top + size - inset).toInt())
                draw(canvas)
            }
            return
        }
        if (useProfileImages && type == BriefCardType.TOP_FOLLOWER) {
            val avatarUrl = TopFollowersStore.read(context, account).top.firstOrNull()?.avatarUrl.orEmpty()
            ProfileImageLoader.cachedCircularBitmap(context, avatarUrl, size.toInt())?.let { avatar ->
                canvas.drawBitmap(avatar, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
                return
            }
        }
        if (useProfileImages && (type == BriefCardType.POST || type == BriefCardType.WORST_POST)) {
            val avatar = TwidgetStore.currentStats(context, account).profileImage
            ProfileImageLoader.cachedCircularBitmap(context, avatar, size.toInt())?.let {
                canvas.drawBitmap(it, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
                return
            }
        }
        val (start, end) = iconGradient(type)
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(left, top, left + size, top + size, start, end, Shader.TileMode.CLAMP)
        }
        canvas.drawOval(RectF(left, top, left + size, top + size), badge)
        ContextCompat.getDrawable(context, supportingIcon(type))?.mutate()?.apply {
            setTint(Color.WHITE)
            val inset = (size * .22f).toInt()
            setBounds(
                (left + inset).toInt(),
                (top + inset).toInt(),
                (left + size - inset).toInt(),
                (top + size - inset).toInt(),
            )
            draw(canvas)
        }
    }

    private fun drawDialLayer(context: Context, canvas: Canvas, @DrawableRes drawable: Int,
        left: Float, top: Float, size: Float, x: Float, y: Float, width: Float, height: Float) {
        val scale = size / 48f
        ContextCompat.getDrawable(context, drawable)?.apply {
            setBounds((left + x * scale).toInt(), (top + y * scale).toInt(),
                (left + (x + width) * scale).toInt(), (top + (y + height) * scale).toInt())
            draw(canvas)
        }
    }

    private fun iconGradient(type: BriefCardType): Pair<Int, Int> = when (type) {
        BriefCardType.GROWTH -> Color.rgb(46, 204, 113) to Color.rgb(42, 139, 242)
        BriefCardType.SLOWDOWN -> Color.rgb(255, 177, 66) to Color.rgb(235, 84, 96)
        BriefCardType.INACTIVITY -> Color.rgb(139, 92, 246) to Color.rgb(77, 162, 255)
        BriefCardType.MILESTONE -> Color.rgb(255, 193, 7) to Color.rgb(255, 111, 97)
        BriefCardType.POST -> Color.rgb(56, 122, 255) to Color.rgb(44, 201, 188)
        BriefCardType.WORST_POST -> Color.rgb(255, 177, 66) to Color.rgb(139, 92, 246)
        BriefCardType.TOP_FOLLOWER -> Color.rgb(69, 188, 255) to Color.rgb(75, 207, 122)
        BriefCardType.STREAK -> Color.rgb(255, 155, 60) to Color.rgb(238, 73, 92)
        BriefCardType.SCHEDULE_GUIDE -> Color.rgb(46, 204, 113) to Color.rgb(56, 122, 255)
        BriefCardType.POST_FOLLOW_THROUGH -> Color.rgb(56, 122, 255) to Color.rgb(44, 201, 188)
        BriefCardType.POSTING_GUIDE -> Color.rgb(139, 92, 246) to Color.rgb(56, 122, 255)
        BriefCardType.SUMMARY -> Color.rgb(77, 162, 255) to Color.rgb(139, 92, 246)
    }

    private fun compactBody(body: String): String {
        val firstSentence = body.substringBefore(". ").trim()
        return if (firstSentence.isBlank() || firstSentence.endsWith('.')) firstSentence else "$firstSentence."
    }

    private fun textPaint(context: Context, fontFamily: String, weight: Int, sizeSp: Float, color: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textSize = sizeSp * context.resources.displayMetrics.scaledDensity
            when (fontFamily) {
                TwidgetStore.FONT_SYSTEM -> typeface = TwidgetFonts.system(weight)
                TwidgetStore.FONT_GOOGLE_SANS_FLEX -> {
                    typeface = TwidgetFonts.googleSansFlex(context)
                    setFontVariationSettings("'wght' $weight, 'wdth' 100, 'ROND' 100, 'GRAD' 0, 'slnt' 0, 'opsz' 18")
                }
                else -> {
                    typeface = TwidgetFonts.oneUiSansVariable(context)
                    setFontVariationSettings("'wght' $weight")
                }
            }
        }

    private fun wrap(text: String, paint: Paint, width: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter(String::isNotBlank)
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && paint.measureText(candidate) > width && lines.size < maxLines - 1) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.take(maxLines).ifEmpty { listOf("") }
    }

    private fun ellipsize(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var value = text
        while (value.isNotEmpty() && paint.measureText("$value…") > width) value = value.dropLast(1)
        return "$value…"
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density
}
