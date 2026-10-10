package com.tjg.twidget.notices

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
import com.tjg.twidget.R

internal object NoticeReaderChrome {
    fun updateInsets(host: FrameLayout, left: Int, top: Int, right: Int) {
        val offset = 0
        host.layoutParams = (host.layoutParams as FrameLayout.LayoutParams).apply {
            marginStart = left + offset
            marginEnd = right
            topMargin = top + offset
        }
    }

    fun install(host: FrameLayout, onBack: () -> Unit) {
        val attrs = host.context.obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.actionBarSize))
        val size = attrs.getDimensionPixelSize(0, (64 * host.resources.displayMetrics.density).toInt())
        attrs.recycle()
        host.removeAllViews()
        host.background = null
        host.elevation = 0f
        host.clipToOutline = false
        host.clipChildren = false
        host.clipToPadding = false
        host.isClickable = false
        host.isFocusable = false
        host.contentDescription = null
        host.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val toolbar = Toolbar(host.context).apply {
            minimumHeight = size
            background = null
            setNavigationIcon(dev.oneuiproject.oneui.R.drawable.ic_oui_back)
            setNavigationContentDescription(R.string.notices_back)
            setNavigationOnClickListener { onBack() }
            contentInsetStartWithNavigation = 0
            setContentInsetsAbsolute(0, 0)
        }
        // Let the native toolbar measure its own button and padding together.
        host.layoutParams = host.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val floating = FloatingToolbarLayout(host.context).apply {
            enableScrollTransition(false, true)
            withAppBarLayout = false
            addView(toolbar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            showFloatingItemBackground(true, false)
        }
        val coordinator = CoordinatorLayout(host.context).apply {
            clipChildren = false
            clipToPadding = false
            addView(floating, CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        host.addView(coordinator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
