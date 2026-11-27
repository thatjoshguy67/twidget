package com.tjg.twidget.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.roundToInt

/** Draw the centre crop in view coordinates without ImageView's canvas matrix. */
internal class DashboardMediaImageView(context: Context) : AppCompatImageView(context) {
    private val source = Rect()
    private val destination = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        val image = drawable as? BitmapDrawable
        val bitmap = image?.bitmap
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        if (bitmap == null || contentWidth <= 0 || contentHeight <= 0) {
            super.onDraw(canvas)
            return
        }
        val scale = maxOf(contentWidth.toFloat() / bitmap.width, contentHeight.toFloat() / bitmap.height)
        val cropWidth = (contentWidth / scale).roundToInt().coerceIn(1, bitmap.width)
        val cropHeight = (contentHeight / scale).roundToInt().coerceIn(1, bitmap.height)
        val left = (bitmap.width - cropWidth) / 2
        val top = (bitmap.height - cropHeight) / 2
        source.set(left, top, left + cropWidth, top + cropHeight)
        destination.set(paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        bitmapPaint.alpha = image.alpha
        bitmapPaint.colorFilter = colorFilter ?: image.colorFilter
        canvas.drawBitmap(bitmap, source, destination, bitmapPaint)
    }
}
