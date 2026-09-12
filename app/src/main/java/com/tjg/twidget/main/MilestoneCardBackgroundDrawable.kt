package com.tjg.twidget.main

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

internal class MilestoneCardBackgroundDrawable(
    private val glowColor: Int,
    private val surfaceColor: Int,
    private val radiusPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val rect = bounds
        paint.shader = RadialGradient(
            rect.left.toFloat(),
            rect.exactCenterY(),
            max(rect.width().toFloat(), 1f),
            intArrayOf(glowColor, blend(glowColor, surfaceColor, 0.5f), surfaceColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            radiusPx,
            radiusPx,
            paint,
        )
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radiusPx)
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun blend(from: Int, to: Int, amount: Float): Int =
        Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
        )
}
