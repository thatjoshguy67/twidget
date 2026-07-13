package com.tjg.twidget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/** A toolbar icon wrapper that keeps the One UI icon tint and adds an untinted status dot. */
class NoticeBadgeDrawable(
    private val icon: Drawable,
    dotColor: Int,
    density: Float,
) : Drawable() {
    private val dotRadius = 3.5f * density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }

    override fun onBoundsChange(bounds: Rect) {
        icon.bounds = bounds
    }

    override fun draw(canvas: Canvas) {
        icon.draw(canvas)
        canvas.drawCircle(
            bounds.right - dotRadius,
            bounds.top + dotRadius,
            dotRadius,
            dotPaint,
        )
    }

    override fun setAlpha(alpha: Int) {
        icon.alpha = alpha
        dotPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        icon.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in the Android framework")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = icon.intrinsicWidth

    override fun getIntrinsicHeight(): Int = icon.intrinsicHeight
}
