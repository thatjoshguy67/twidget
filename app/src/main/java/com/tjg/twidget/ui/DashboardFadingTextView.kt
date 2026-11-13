package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.appcompat.widget.AppCompatTextView

/** Fade the text's alpha so the card's normal and pressed surfaces both show through. */
internal class DashboardFadingTextView(context: Context) : AppCompatTextView(context), DashboardCardDesign.Scalable {
    private var designScale = 1f
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var shaderStart = Float.NaN
    private var shaderEnd = Float.NaN
    override fun applyCardDesignScale(scale: Float) { designScale = scale }
    override fun onDraw(canvas: Canvas) {
        if ((layout?.height ?: 0) <= height - compoundPaddingTop - compoundPaddingBottom) {
            super.onDraw(canvas)
            return
        }
        val unit = resources.displayMetrics.density * designScale
        val bottom = (height - compoundPaddingBottom - 2f * unit).coerceAtLeast(1f)
        val fadeHeight = minOf(50f * unit, lineHeight.toFloat())
        val start = (bottom - fadeHeight).coerceAtLeast(compoundPaddingTop.toFloat()).coerceAtMost(bottom - 1f)
        if (shaderStart != start || shaderEnd != bottom) {
            fadePaint.shader = LinearGradient(0f, start, 0f, bottom,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            shaderStart = start
            shaderEnd = bottom
        }
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.clipRect(0, 0, width, height)
        super.onDraw(canvas)
        canvas.drawRect(0f, start, width.toFloat(), height.toFloat(), fadePaint)
        canvas.restoreToCount(checkpoint)
    }
}
