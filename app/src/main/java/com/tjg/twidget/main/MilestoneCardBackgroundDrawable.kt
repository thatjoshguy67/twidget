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
import com.tjg.twidget.ui.DashboardCardDesign

internal class MilestoneCardBackgroundDrawable(
    private val glowColor: Int,
    private val surfaceColor: Int,
    private val radiusPx: Float,
) : Drawable(), DashboardCardDesign.Scalable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var designScale = 1f
    override fun applyCardDesignScale(scale: Float) { designScale = scale }

    override fun draw(canvas: Canvas) {
        val rect = bounds
        paint.shader = RadialGradient(
            rect.left.toFloat(),
            rect.exactCenterY(),
            max(rect.width().toFloat(), 1f),
            intArrayOf(glowColor, surfaceColor),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        ).apply {
            setLocalMatrix(android.graphics.Matrix().apply {
                setScale(1f, 361.96f / 538f, rect.left.toFloat(), rect.exactCenterY())
            })
        }
        canvas.drawRoundRect(
            rect.left.toFloat(),
            rect.top.toFloat(),
            rect.right.toFloat(),
            rect.bottom.toFloat(),
            radiusPx * designScale,
            radiusPx * designScale,
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
        outline.setRoundRect(bounds, radiusPx * designScale)
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
