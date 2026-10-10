package com.tjg.twidget.schedule

import android.view.Gravity
import android.view.Menu
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.oneui.floatingactioncontainer.FloatingBottomLayout
import com.tjg.twidget.R
import dev.oneuiproject.oneui.design.R as DesignR

internal object ScheduleComposeChrome {
    fun install(root: ViewGroup) {
        val legacy = root.findViewById<ViewGroup>(R.id.schedule_compose_bottom_bar)
        val controls = legacy.getChildAt(0)
        legacy.removeView(controls)
        (legacy.parent as ViewGroup).removeView(legacy)
        // The native projection owns the surface, elevation and shadow.
        controls.background = null
        controls.elevation = 0f
        controls.translationZ = 0f
        val scroll = root.findViewById<NestedScrollView>(R.id.schedule_compose_scroll)
        val floating = FloatingBottomLayout(root.context).apply {
            id = R.id.schedule_compose_bottom_bar
            enableScrollTransition(false, true)
            addView(controls, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ))
            showFloatingItemBackground(true, false)
            setNestedScrollView(scroll)
        }
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
            val clearance = floating.height + (floating.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
            if (scroll.paddingBottom != clearance) {
                scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, clearance)
            }
        }
    }

    fun prepareMenu(menu: Menu, draftEnabled: Boolean, saveEnabled: Boolean) {
        menu.findItem(R.id.schedule_compose_draft_button)?.isEnabled = draftEnabled
        menu.findItem(R.id.schedule_compose_save)?.isEnabled = saveEnabled
    }
}
