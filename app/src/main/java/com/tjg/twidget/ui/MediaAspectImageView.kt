package com.tjg.twidget.ui

import android.content.Context
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.roundToInt

/** Keeps decoded aspect ratio until the configured height cap requires cropping. */
class MediaAspectImageView(context: Context) : AppCompatImageView(context) {
    var maximumMediaHeightPx: Int = Int.MAX_VALUE
        set(value) { field = value; requestLayout() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val image = drawable
        if (image == null || image.intrinsicWidth <= 0 || image.intrinsicHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val ratio = image.intrinsicWidth.toFloat() / image.intrinsicHeight
        when {
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY &&
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY -> {
                val height = MeasureSpec.getSize(heightMeasureSpec)
                setMeasuredDimension(resolveSize((height * ratio).roundToInt(), widthMeasureSpec), height)
            }
            MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY &&
                MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY -> {
                val width = MeasureSpec.getSize(widthMeasureSpec)
                val naturalHeight = (width / ratio).roundToInt()
                val height = resolveSize(minOf(naturalHeight, maximumMediaHeightPx), heightMeasureSpec)
                if (maximumMediaHeightPx != Int.MAX_VALUE) {
                    scaleType = if (height < naturalHeight) ScaleType.CENTER_CROP else ScaleType.FIT_CENTER
                }
                setMeasuredDimension(width, height)
            }
            else -> super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
