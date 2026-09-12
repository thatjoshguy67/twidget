package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCard
import com.tjg.twidget.brief.BriefCardType
import com.tjg.twidget.brief.BriefEditorialSummary
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
            iconInsetDp = 14f,
            textInsetDp = 16f,
            bottomInsetDp = 16f,
            iconSizeDp = minOf(heightDp * 0.245f, widthDp * 0.28f),
            titleSizeSp = if (widthDp <= 230f) 18f else 20f,
            bodySizeSp = if (compactWidth) 12f else 14f,
            textGapDp = 6f,
            titleWeight = 600,
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
    ): Bitmap {
        val width = widthPx.coerceAtLeast(dp(context, 100))
        val height = heightPx.coerceAtLeast(dp(context, 56))
        val density = context.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val card = snapshot?.cards?.firstOrNull() ?: BriefCard(
            id = "empty",
            type = BriefCardType.SUMMARY,
            title = context.getString(R.string.brief_widget_empty_title),
            body = context.getString(R.string.brief_widget_empty_body),
            score = 0,
        )
        val summary = snapshot?.let(BriefEditorialSummary::from)
        val displayCard = card.copy(
            title = summary?.title ?: card.title,
            body = summary?.shortDescription ?: card.body,
        )
        val widgetLayout = layout(width / density, height / density)
        val primary = if (dark) Color.WHITE else Color.rgb(18, 18, 20)
        val secondary = primary

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
                    maxLines = 1,
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
                    bodyLines = 2,
                    iconStartDp = metrics.iconInsetDp,
                    iconTopDp = metrics.iconInsetDp,
                    bottomPaddingDp = metrics.bottomInsetDp,
                    gapDp = metrics.textGapDp,
                    titleWeight = metrics.titleWeight,
                    fontFamily = fontFamily,
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
        val lines = wrap(title, paint, width, maxLines)
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
    ) {
        val pad = dp(context, paddingDp)
        val iconStart = dp(context, iconStartDp)
        val iconTop = dp(context, iconTopDp)
        val bottomPad = dp(context, bottomPaddingDp)
        val iconSize = minOf(dp(context, iconSizeDp), heightPx - iconTop - bottomPad)
        drawStateIcon(context, canvas, card.type, account, iconStart, iconTop, iconSize)

        val textWidth = widthPx - pad * 2f
        val titlePaint = textPaint(context, fontFamily, titleWeight, titleSizeSp, primary)
        val bodyPaint = textPaint(context, fontFamily, 400, bodySizeSp, secondary)
        val wrappedTitle = wrap(card.title, titlePaint, textWidth, titleLines)
        val body = if (bodyLines <= 2 && widthPx <= dp(context, 300)) compactBody(card.body) else card.body
        val wrappedBody = wrap(body, bodyPaint, textWidth, bodyLines)
        val titleLineHeight = titlePaint.textSize * 1.10f
        val bodyLineHeight = bodyPaint.textSize * 1.16f
        val titleHeight = titleLineHeight * wrappedTitle.size
        val bodyHeight = bodyLineHeight * wrappedBody.size
        val gap = dp(context, gapDp)
        val blockTop = heightPx - bottomPad - titleHeight - gap - bodyHeight

        var baseline = blockTop - titlePaint.fontMetrics.top
        wrappedTitle.forEachIndexed { index, line ->
            canvas.drawText(
                ellipsize(line, titlePaint, textWidth),
                pad,
                baseline + index * titleLineHeight,
                titlePaint,
            )
        }
        baseline = blockTop + titleHeight + gap - bodyPaint.fontMetrics.top
        wrappedBody.forEachIndexed { index, line ->
            canvas.drawText(
                ellipsize(line, bodyPaint, textWidth),
                pad,
                baseline + index * bodyLineHeight,
                bodyPaint,
            )
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
    ) {
        if (type == BriefCardType.TOP_FOLLOWER) {
            val avatarUrl = TopFollowersStore.read(context, account).top.firstOrNull()?.avatarUrl.orEmpty()
            ProfileImageLoader.cachedCircularBitmap(context, avatarUrl, size.toInt())?.let { avatar ->
                canvas.drawBitmap(avatar, left, top, Paint(Paint.ANTI_ALIAS_FLAG))
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
            typeface = if (fontFamily == TwidgetStore.FONT_GOOGLE_SANS_FLEX) {
                TwidgetFonts.googleSansFlex(context)
            } else {
                TwidgetFonts.oneUiSansVariable(context)
            }
            setFontVariationSettings("'wght' $weight")
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
