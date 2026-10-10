package com.tjg.twidget.widget

import kotlin.math.sqrt

internal data class WidgetBitmapSize(val width: Int, val height: Int)

/** Lower the raster resolution without changing the renderer's logical layout dimensions. */
internal fun widgetBitmapSize(width: Int, height: Int, budgetBytes: Long): WidgetBitmapSize {
    require(width > 0 && height > 0)
    require(budgetBytes >= 4)
    val maxPixels = budgetBytes / 4
    val pixels = width.toLong() * height
    if (pixels <= maxPixels) return WidgetBitmapSize(width, height)
    val scale = sqrt(maxPixels.toDouble() / pixels)
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        .coerceAtMost(minOf(maxPixels, Int.MAX_VALUE.toLong()).toInt())
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        .coerceAtMost(minOf(maxPixels / scaledWidth, Int.MAX_VALUE.toLong()).toInt())
    return WidgetBitmapSize(scaledWidth, scaledHeight)
}
