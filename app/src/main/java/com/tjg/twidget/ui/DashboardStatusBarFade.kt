package com.tjg.twidget.ui

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Live scrolling remains visible beneath system chrome, with a gradual fade into the surface. */
internal object DashboardStatusBarFade {
    fun install(root: View, scrolled: () -> Boolean) {
        val value = TypedValue()
        root.context.theme.resolveAttribute(androidx.appcompat.R.attr.roundedCornerColor, value, true)
        val surface = if (value.resourceId != 0) ContextCompat.getColor(root.context, value.resourceId) else value.data
        val fade = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(surface, surface and 0x00ffffff)).apply { alpha = 0 }
        root.overlay.add(fade)
        root.viewTreeObserver.addOnPreDrawListener {
            val top = ViewCompat.getRootWindowInsets(root)?.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
            val height = top + (16 * root.resources.displayMetrics.density).toInt()
            fade.setBounds(0, 0, root.width, height)
            fade.alpha = if (top > 0 && scrolled()) 255 else 0
            true
        }
    }
}
