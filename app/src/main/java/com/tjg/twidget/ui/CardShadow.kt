package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import com.tjg.twidget.R

internal object CardShadow {
    const val PADDING_DP = 36
    private val patches = object : android.util.LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    fun paint(context: Context, blurDp: Float = 30f, offsetDp: Float = 6f,
              shadowColor: Int = context.getColor(R.color.card_preview_shadow)) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_card_bg)
        val density = context.resources.displayMetrics.density
        setShadowLayer(blurDp * density, 0f, offsetDp * density, shadowColor)
    }

    /** Blur one small rounded patch once; stretch only its straight edges on the GPU. */
    fun renderer(context: Context, radius: Float, blurDp: Float = 30f, offsetDp: Float = 6f,
                 shadowColor: Int = context.getColor(R.color.card_preview_shadow)): Renderer {
        val padding = PADDING_DP * context.resources.displayMetrics.density
        val edge = kotlin.math.ceil(padding + radius).toInt()
        val size = edge * 2 + 1
        val key = "$edge:$padding:$radius:$blurDp:$offsetDp:$shadowColor:${context.getColor(R.color.oneui_card_bg)}"
        val patch = patches[key] ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRoundRect(padding, padding, size - padding, size - padding,
                radius, radius, paint(context, blurDp, offsetDp, shadowColor))
            patches.put(key, it)
        }
        return Renderer(patch, edge, padding)
    }

    class Renderer(private val patch: Bitmap, private val edge: Int, private val padding: Float) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val source = Rect()
        private val target = RectF()
        private val x = FloatArray(4)
        private val y = FloatArray(4)
        private val cuts = intArrayOf(0, edge, edge + 1, patch.width)
        fun draw(canvas: Canvas, width: Int, height: Int) {
            val corner = edge - padding
            x[1] = padding + corner
            x[2] = width - padding - corner
            x[3] = width.toFloat()
            y[1] = padding + corner
            y[2] = height - padding - corner
            y[3] = height.toFloat()
            for (row in 0..2) for (column in 0..2) {
                source.set(cuts[column], cuts[row], cuts[column + 1], cuts[row + 1])
                target.set(x[column], y[row], x[column + 1], y[row + 1])
                canvas.drawBitmap(patch, source, target, paint)
            }
        }
    }
}

/** Includes a clickable card's surface and ripple mask in the same shape. */
internal fun Drawable.setCardCornerRadius(radius: Float) {
    when (this) {
        is GradientDrawable -> cornerRadius = radius
        is LayerDrawable -> for (index in 0 until numberOfLayers) {
            getDrawable(index).setCardCornerRadius(radius)
        }
    }
}

internal class CardShadowView(context: Context, private val radius: Float) : View(context) {
    private val renderer = CardShadow.renderer(context, radius, blurDp = 8f, offsetDp = 2f,
        shadowColor = context.getColor(R.color.dashboard_edit_shadow))
    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height)
    }
}
