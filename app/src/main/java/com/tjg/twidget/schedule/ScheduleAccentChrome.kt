package com.tjg.twidget.schedule

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetTheme
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
/**
 * Re-applies the current accent to scheduling chrome that caches colors at inflation
 * (FABs, list/calendar tabs, pull-to-refresh, selection bars). Scheduling-only — do not
 * use for app-wide theme surfaces.
 *
 * FAB tints are set here only (not in layout XML) so accent changes never stick to an
 * old resolved `?attr/colorPrimary`.
 */
object ScheduleAccentChrome {
    fun apply(
        activity: Activity,
        primaryFab: FloatingActionButton? = null,
        tabs: TabLayout? = null,
        refresh: SwipeRefreshLayout? = null,
        selectionBottomNav: BottomNavigationView? = null,
        trashBottomNav: BottomNavigationView? = null,
    ) {
        val accent = TwidgetTheme.accent(activity)
        val tint = ColorStateList.valueOf(accent)

        activity.findViewById<FloatingActionButton>(R.id.schedule_fab)?.let { applyFab(it, tint) }
        primaryFab?.let { applyFab(it, tint) }

        activity.findViewById<View>(R.id.schedule_queue_container)
            ?.setBackgroundColor(TwidgetTheme.background(activity))

        refresh?.setColorSchemeColors(accent)
        tabs?.let { applyTabs(it, accent) }
        selectionBottomNav?.let { applyBottomNav(it, tint) }
        trashBottomNav?.let { applyBottomNav(it, tint) }
    }

    private fun applyFab(fab: FloatingActionButton, tint: ColorStateList) {
        ViewCompat.setBackgroundTintList(fab, tint)
        fab.backgroundTintList = tint
        fab.backgroundTintMode = PorterDuff.Mode.SRC_IN
        fab.imageTintList = ColorStateList.valueOf(Color.WHITE)
        fab.post {
            if (!fab.isAttachedToWindow) return@post
            ViewCompat.setBackgroundTintList(fab, tint)
            fab.backgroundTintList = tint
        }
    }
    private fun applyTabs(tabs: TabLayout, accent: Int) {
        val radius = 18f * tabs.resources.displayMetrics.density
        val selected = GradientDrawable().apply {
            cornerRadius = radius
            setColor(accent)
        }
        val unselected = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0)
        }
        for (index in 0 until tabs.tabCount) {
            val tab = tabs.getTabAt(index) ?: continue
            val isSelected = index == tabs.selectedTabPosition
            val background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_selected), selected.constantState?.newDrawable()?.mutate())
                addState(intArrayOf(), unselected.constantState?.newDrawable()?.mutate())
            }
            tab.customView?.apply {
                this.background = background
                this.isSelected = isSelected
                refreshDrawableState()
            }
            tab.view.isSelected = isSelected
        }
    }

    private fun applyBottomNav(nav: BottomNavigationView, tint: ColorStateList) {
        nav.itemIconTintList = tint
        nav.itemTextColor = tint
    }
}
