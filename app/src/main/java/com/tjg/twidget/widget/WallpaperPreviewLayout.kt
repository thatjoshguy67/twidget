package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import dev.oneuiproject.oneui.widget.RoundedFrameLayout

/** Reveals the system wallpaper surface without reading the protected wallpaper bitmap. */
class WallpaperPreviewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RoundedFrameLayout(context, attrs, defStyleAttr) {
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Clear the ancestor page backgrounds from the window surface, then let
        // One UI draw the preview children and its opaque rounded-corner masks.
        // Do not use a saveLayer or a view layer: that would only clear that layer.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), clearPaint)
        super.dispatchDraw(canvas)
    }
}
