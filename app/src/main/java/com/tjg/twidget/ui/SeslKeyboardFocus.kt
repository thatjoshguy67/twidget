package com.tjg.twidget.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.tjg.twidget.R

/** Composes keyboard-only focus with existing foregrounds, including ripple and surface rims. */
internal object SeslKeyboardFocus {
    fun install(view: View, radiusDp: Float) {
        val previous = view.getTag(R.id.floating_focus_binding) as? FocusRing
        val ring = previous ?: FocusRing(view, radiusDp * view.resources.displayMetrics.density)
        fun contains(drawable: Drawable?): Boolean = drawable === ring ||
            (drawable is LayerDrawable && (0 until drawable.numberOfLayers).any { contains(drawable.getDrawable(it)) })
        if (!contains(view.foreground)) {
            view.foreground = view.foreground?.let { LayerDrawable(arrayOf(it, ring)) } ?: ring
        }
        if (previous != null) return
        view.setTag(R.id.floating_focus_binding, ring)
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

    /** One observer per window also covers recycled preference rows and lazy dialogs. */
    fun observeWindow(root: View) {
        fun visit(view: View) {
            if (view.isFocusable && (view.isClickable || view is android.widget.EditText)) {
                install(view, if (view is androidx.appcompat.widget.SearchView) 28f else 12f)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        if (root.getTag(R.id.floating_keyboard_observer) != null) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener { visit(root) }
        root.setTag(R.id.floating_keyboard_observer, listener)
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (v.getTag(R.id.floating_keyboard_observer) == null) observeWindow(v)
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                v.setTag(R.id.floating_keyboard_observer, null)
                v.removeOnAttachStateChangeListener(this)
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
            if (!(view.isFocused || view is androidx.appcompat.widget.SearchView && view.hasFocus()) || view.isInTouchMode) return
            val inset = paint.strokeWidth / 2f + view.resources.displayMetrics.density
            canvas.drawRoundRect(bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset, radius, radius, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
