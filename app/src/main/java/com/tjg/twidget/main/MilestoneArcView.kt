package com.tjg.twidget.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** One UI's rounded 228° dial, adapted from codex-meter's WidgetGraphics. */
class MilestoneArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    var progressColor: Int = Color.rgb(24, 129, 255)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = 6f * resources.displayMetrics.density
        paint.strokeWidth = stroke
        val inset = stroke / 2f + 2f * resources.displayMetrics.density
        bounds.set(inset, inset, width - inset, height - inset)
        paint.color = (progressColor and 0x00FFFFFF) or 0x52000000
        canvas.drawArc(bounds, ARC_START, ARC_SWEEP, false, paint)
        if (progress > 0) {
            paint.color = progressColor
            canvas.drawArc(bounds, ARC_START, ARC_SWEEP * progress / 100f, false, paint)
        }
    }

    private companion object {
        const val ARC_START = 156f
        const val ARC_SWEEP = 228f
    }
}
