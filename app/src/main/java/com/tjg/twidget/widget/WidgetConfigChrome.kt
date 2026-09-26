package com.tjg.twidget.widget

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.oneui.dividerbuttonlayout.DividerButtonLayout
import com.google.android.material.oneui.floatingactioncontainer.FloatingBottomLayout
import com.tjg.twidget.R
import dev.oneuiproject.oneui.design.R as DesignR

/** Uses SESL's button sizing, divider and floating projection without a custom surface. */
internal object WidgetConfigChrome {
    fun install(root: ViewGroup) {
        val oldBar = root.findViewById<View>(R.id.config_button_bar)
        (oldBar.parent as ViewGroup).removeView(oldBar)
        val scroll = root.findViewById<NestedScrollView>(R.id.widget_settings_scroll)
        // On One UI / API 36+, SESL's transparent edge fade wraps the top and
        // bottom strips in saveUnclippedLayer. The wallpaper preview's CLEAR
        // then clears that temporary layer, exposing the opaque page instead of
        // the wallpaper and leaving a straight cutoff at the layer boundary.
        // Keep this window-backed preview out of fade layers; other screens keep
        // the standard SESL fading installed by SeslToolbarCompatibility.
        scroll.seslSetFadingEdgeEnabled(false)
        // Stretch overscroll also isolates the content in a hardware layer,
        // hiding the wallpaper opening until the edge bounce has settled.
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        val buttons = DividerButtonLayout(root.context).apply {
            inflateMenu(R.menu.widget_config_actions)
        }
        val floating = FloatingBottomLayout(root.context).apply {
            id = R.id.config_button_bar
            enableScrollTransition(false, true)
            addView(buttons, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ))
            showFloatingItemBackground(true, false)
            setNestedScrollView(scroll)
        }
        // Keep actions fixed while the native app bar and settings list scroll.
        val overlay = FrameLayout(root.context).apply {
            clipChildren = false
            clipToPadding = false
            addView(floating, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ))
        }
        root.findViewById<CoordinatorLayout>(DesignR.id.toolbarlayout_coordinator_layout)
            .addView(overlay, CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        floating.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val margin = (floating.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            val clearance = floating.height + margin
            if (scroll.paddingBottom != clearance) {
                scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, clearance)
            }
        }
    }
}
