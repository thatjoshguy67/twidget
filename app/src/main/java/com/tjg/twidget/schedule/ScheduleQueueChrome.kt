package com.tjg.twidget.schedule

import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.graphics.Typeface
import android.widget.TextView
import com.tjg.twidget.R
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.widget.RoundedNestedScrollView
import dev.oneuiproject.oneui.widget.RoundedTabLayout
import dev.oneuiproject.oneui.R as IconR
import dev.oneuiproject.oneui.design.R as DesignR

/** Native SESL9 navigation/actions, positioned independently of app-bar scrolling. */
internal class ScheduleQueueChrome(
    private val toolbar: ToolbarLayout,
    private val root: View,
    private val fab: FloatingActionButton,
    private val tabs: RoundedTabLayout,
    private val selection: BottomNavigationView,
    private val trash: BottomNavigationView,
    private val scroll: RoundedNestedScrollView,
) {
    val floating = true
    val snackbarAnchor: View?
        get() = listOf(fab, selection, trash, navigationRow).firstOrNull { it.isShown }
    private val overlay = FrameLayout(root.context).apply {
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
    }
    private val navigation = LayoutInflater.from(root.context)
        .inflate(R.layout.schedule_floating_navigation, overlay, false) as BottomNavigationView
    private val navigationRow = navigation
    private var navigationInset = 0
    private var switcherVisible = true
    private val listId = View.generateViewId()
    private val calendarId = View.generateViewId()
    private val preDraw = ViewTreeObserver.OnPreDrawListener { update(); true }
    val contentBottomInset: Int
        get() = navigationInset + dp(32) + maxOf(navigationRow.height, selection.height, trash.height, dp(80)) +
            if (fab.visibility == View.VISIBLE) dp(82) else 0

    init {
        val coordinator = toolbar.findViewById<CoordinatorLayout>(DesignR.id.toolbarlayout_coordinator_layout)
        coordinator.addView(overlay, CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        navigation.menu.add(0, listId, 0, R.string.schedule_view_list).setIcon(IconR.drawable.ic_oui_list)
        navigation.menu.add(0, calendarId, 1, R.string.schedule_view_calendar).setIcon(IconR.drawable.ic_oui_calendar_task)
        for (index in 0 until navigation.menu.size()) {
            navigation.menu.getItem(index).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        navigation.menu.setGroupCheckable(0, true, true)
        navigation.menu.findItem(listId).isChecked = true
        navigation.setOnItemSelectedListener { item ->
            tabs.getTabAt(if (item.itemId == listId) 0 else 1)?.select()
            true
        }
        overlay.addView(navigation, bottomParams())
        navigation.setItemBackgroundResource(R.drawable.schedule_navigation_item_background)
        for (bar in listOf(selection, trash)) {
            (bar.parent as ViewGroup).removeView(bar)
            overlay.addView(bar, bottomParams())
            // These are commands, not destinations: never show a selected-tab pill.
            for (index in 0 until bar.menu.size()) bar.menu.getItem(index).isCheckable = false
        }
        (fab.parent as ViewGroup).removeView(fab)
        fab.customSize = dp(70)
        overlay.addView(fab, FrameLayout.LayoutParams(dp(70), dp(70), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20)
        })
        root.viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    fun updateSelectionCount(count: Int) {
        toolbar.findViewById<TextView>(DesignR.id.toolbar_layout_action_mode_title)?.apply {
            contentDescription = text
            text = java.text.NumberFormat.getIntegerInstance().format(count)
        }
    }

    fun setSwitcherVisible(visible: Boolean) {
        switcherVisible = visible
        update()
    }

    fun updateInsets(inset: Int) {
        navigationInset = inset
        update()
    }

    private fun update() {
        overlay.visibility = if (root.isShown) View.VISIBLE else View.GONE
        if (overlay.visibility != View.VISIBLE) return
        navigationRow.visibility = if (switcherVisible && selection.visibility != View.VISIBLE && trash.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        val selectedId = if (tabs.selectedTabPosition == 1) calendarId else listId
        if (!navigation.menu.findItem(selectedId).isChecked) {
            // Assign checked state directly; selecting programmatically would invoke
            // the tab listener again while a queue render is in progress.
            navigation.menu.findItem(selectedId).isChecked = true
        }
        // This SESL build keeps both label views visible. Set boldness per
        // destination so the inactive label stays regular, as in Samsung's nav.
        for (index in 0 until navigation.menu.size()) {
            val item = navigation.menu.getItem(index)
            val view = navigation.findViewById<ViewGroup>(item.itemId) ?: continue
            val checked = item.itemId == selectedId
            styleLabels(view, if (checked) Typeface.BOLD else Typeface.NORMAL)
            view.isSelected = checked
        }
        val margin = navigationInset + dp(16)
        for (bar in listOf(navigationRow, selection, trash)) {
            val params = bar.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin
                bar.layoutParams = params
            }
            bar.translationY = 0f
        }
        val fabParams = fab.layoutParams as FrameLayout.LayoutParams
        val fabMargin = if (navigationRow.visibility == View.VISIBLE) margin + maxOf(navigationRow.height, dp(80)) + dp(12)
            else navigationInset + dp(20)
        if (fabParams.bottomMargin != fabMargin) {
            fabParams.bottomMargin = fabMargin
            fab.layoutParams = fabParams
        }
        val bottom = contentBottomInset
        if (scroll.paddingBottom != bottom) {
            scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, bottom)
        }
    }

    private fun styleLabels(group: ViewGroup, style: Int) {
        for (index in 0 until group.childCount) {
            when (val child = group.getChildAt(index)) {
                is TextView -> if (child.typeface?.style != style) child.setTypeface(child.typeface, style)
                is ViewGroup -> styleLabels(child, style)
            }
        }
    }

    private fun bottomParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { leftMargin = dp(16); rightMargin = dp(16) }

    private fun dp(value: Int) = (value * root.resources.displayMetrics.density).toInt()

    fun dispose() {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(preDraw)
    }
}
