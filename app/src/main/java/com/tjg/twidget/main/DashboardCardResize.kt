package com.tjg.twidget.main

import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.DashboardRecyclerView

/** A menu action selects one native span state and records one undoable change. */
internal class DashboardCardResize(private val activity: MainActivity) {
    private var active = false
    val isActive get() = active

    fun toggle(card: DashboardCardType) {
        if (active || !card.supportsSizeVariants) return
        val recycler = activity.findViewById<DashboardRecyclerView>(R.id.dashboard_content) ?: return
        val wrapper = recycler.findViewWithTag<FrameLayout>(card.id) ?: return
        val params = wrapper.layoutParams as? GridLayoutManager.LayoutParams ?: return
        if (!activity.editModeController.beginResize()) return
        active = true
        try {
            recycler.stopScroll()
            recycler.itemAnimator?.endAnimations()
            val account = activity.selectedAccount
            val order = TwidgetStore.dashboardCards(activity, account)
            val spans = activity.dashboardBinder.cardIds().associateWith { DashboardCardType.fromId(it)!!.span(activity, account) }
            val target = if (card.span(activity, account) == 1) 2 else 1
            val rtl = recycler.layoutDirection == View.LAYOUT_DIRECTION_RTL
            // A card at the trailing edge expands inward, including in four
            // columns. Other cards keep their reading-order starting edge.
            val inward = target == 2 && params.spanIndex + params.spanSize >= recycler.columnCount
            val edge = if (inward != rtl) DashboardResizeEdge.LEFT else DashboardResizeEdge.RIGHT
            val next = DashboardResizePolicy.orderForResize(order, spans, card.id, target, edge, rtl, recycler.columnCount)
            activity.dashboardBinder.previewCardResize(card, target, next)
            activity.editModeController.finishResize(card, target, next, commit = true)
        } finally {
            active = false
        }
    }
    fun dispose() = Unit // Size changes commit atomically; no fractional gesture state remains.
}
