package com.tjg.twidget.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.AttributeSet
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.appcompat.util.SeslRoundedCorner

/** Reveals the wallpaper surface without reading the protected wallpaper bitmap. */
class WallpaperPreviewLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val cornerRadius = resources.getDimensionPixelSize(androidx.appcompat.R.dimen.sesl_rounded_corner_radius).toFloat()
    private var opening = Path()
    private val visibleBounds = Rect()
    private val lastVisibleBounds = Rect()
    private val position = IntArray(2)
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    private val redrawOpening = ViewTreeObserver.OnPreDrawListener {
        getLocationInWindow(position)
        visibleBounds.setEmpty()
        getGlobalVisibleRect(visibleBounds)
        if (position[0] != lastX || position[1] != lastY || visibleBounds != lastVisibleBounds) {
            lastX = position[0]
            lastY = position[1]
            lastVisibleBounds.set(visibleBounds)
            // Scrolling normally reuses this view's display list. A clear operation
            // also changes pixels painted by ancestors, so repaint the window when
            // the opening moves or becomes visible again, including during flings.
            invalidate()
            rootView.invalidate()
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(redrawOpening)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(redrawOpening)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Use SESL's native contour anchored to the view, never Canvas.clipBounds:
        // the latter changes when a scroll parent clips or partially redraws us.
        opening = SeslRoundedCorner.getSmoothCornerRectPath(cornerRadius, 0f, 0f, w.toFloat(), h.toFloat())
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Clear directly into the window. An offscreen layer would only clear its
        // own texture and leave the opaque page underneath the preview.
        canvas.drawPath(opening, clearPaint)
        val save = canvas.save()
        canvas.clipPath(opening)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
