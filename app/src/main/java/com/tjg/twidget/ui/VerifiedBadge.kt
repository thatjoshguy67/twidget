package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import androidx.appcompat.content.res.AppCompatResources
import com.tjg.twidget.R
import kotlin.math.roundToInt

// Appends status badges after a display name wherever it is rendered as text
// (header title, drawer items, account rows).
object VerifiedBadge {
    fun decorate(
        context: Context,
        name: CharSequence,
        verified: Boolean?,
        isPrivate: Boolean?,
        badgeSizePx: Int,
        scaleWithText: Boolean = false,
    ): CharSequence {
        if (name.isBlank() || (verified != true && isPrivate != true)) return name
        val builder = SpannableStringBuilder(name)
        if (verified == true) {
            builder.appendBadge(context, R.drawable.ic_x_verified, badgeSizePx, scaleWithText, R.color.oneui_accent)
        }
        if (isPrivate == true) {
            builder.appendBadge(context, R.drawable.ic_x_lock, badgeSizePx, scaleWithText, R.color.oneui_text_secondary)
        }
        return builder
    }

    private fun SpannableStringBuilder.appendBadge(
        context: Context,
        drawableRes: Int,
        sizePx: Int,
        scaleWithText: Boolean,
        tintRes: Int,
    ) {
        val badge = AppCompatResources.getDrawable(context, drawableRes)?.mutate() ?: return
        badge.setTint(context.getColor(tintRes))
        val alignment = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageSpan.ALIGN_CENTER
        } else {
            ImageSpan.ALIGN_BASELINE
        }
        append("  ")
        setSpan(
            ScalingBadgeSpan(badge, sizePx, scaleWithText, alignment),
            length - 1,
            length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * The collapsing header draws one title at two text sizes. A fixed image
     * span stays at the expanded size, so the badge follows the paint instead.
     * When scaling dynamically with text, the badge matches the paint's text size.
     */
    private class ScalingBadgeSpan(
        drawable: Drawable,
        private val sizeAtReference: Int,
        private val scaleWithText: Boolean,
        private val alignment: Int,
    ) : ImageSpan(drawable, alignment) {
        private fun size(paint: Paint): Int {
            if (!scaleWithText) return sizeAtReference
            return paint.textSize.roundToInt().coerceAtLeast(1)
        }

        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let {
                val metrics = paint.fontMetricsInt
                it.ascent = metrics.ascent
                it.descent = metrics.descent
                it.top = metrics.top
                it.bottom = metrics.bottom
            }
            return size(paint)
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val badge = drawable ?: return
            val size = size(paint)
            badge.setBounds(0, 0, size, size)
            val transY = if (alignment == ALIGN_CENTER) {
                top + (bottom - top - size) / 2
            } else {
                y + paint.fontMetricsInt.descent - size
            }
            canvas.save()
            canvas.translate(x, transY.toFloat())
            badge.draw(canvas)
            canvas.restore()
        }
    }
}
