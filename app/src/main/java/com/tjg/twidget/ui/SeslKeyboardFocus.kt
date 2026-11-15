package com.tjg.twidget.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver

/** Composes keyboard-only focus with existing foregrounds, including ripple and surface rims. */
internal object SeslKeyboardFocus {
    fun install(view: View, radiusDp: Float) {
        val ring = FocusRing(view, radiusDp * view.resources.displayMetrics.density)
        view.foreground = view.foreground?.let { LayerDrawable(arrayOf(it, ring)) } ?: ring
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> ring.invalidateSelf() }
        val touch = ViewTreeObserver.OnTouchModeChangeListener { ring.invalidateSelf() }
        fun attach() {
            view.viewTreeObserver.addOnGlobalFocusChangeListener(listener)
            view.viewTreeObserver.addOnTouchModeChangeListener(touch)
        }
        if (view.isAttachedToWindow) attach()
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = attach()
            override fun onViewDetachedFromWindow(v: View) {
                if (v.viewTreeObserver.isAlive) {
                    v.viewTreeObserver.removeOnGlobalFocusChangeListener(listener)
                    v.viewTreeObserver.removeOnTouchModeChangeListener(touch)
                }
            }
        })
    }

    private class FocusRing(val view: View, val radius: Float) : Drawable() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * view.resources.displayMetrics.density
            val value = TypedValue()
            view.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)
            color = value.data
        }
        override fun draw(canvas: Canvas) {
            if (!view.hasFocus() || view.isInTouchMode) return
            val inset = paint.strokeWidth / 2f + view.resources.displayMetrics.density
            canvas.drawRoundRect(bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset, radius, radius, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
