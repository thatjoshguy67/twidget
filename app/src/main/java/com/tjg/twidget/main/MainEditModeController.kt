package com.tjg.twidget.main

import android.view.DragEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.data.TwidgetStore

internal class MainEditModeController(
    private val activity: MainActivity,
) {
    var editMode = false
        private set

    var draggedCardId: String? = null
    var dragPreviewOrder: List<String>? = null
    var dragPlaceholderView: android.view.View? = null
    var dragSourceView: android.view.View? = null

    private var autoScrollDirection = 0
    private var autoScrollScheduled = false
    private val autoScrollStep: Runnable = object : Runnable {
        override fun run() {
            autoScrollScheduled = false
            val scroll = activity.findViewById<NestedScrollView>(R.id.dashboard_scroll) ?: return
            if (autoScrollDirection == 0 || draggedCardId == null) return
            if (!scroll.canScrollVertically(autoScrollDirection)) {
                autoScrollDirection = 0
                return
            }
            scroll.scrollBy(0, activity.dp(AUTO_SCROLL_STEP_DP) * autoScrollDirection)
            autoScrollScheduled = true
            scroll.postOnAnimation(this)
        }
    }

    val exitEditModeOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setEditMode(false)
        }
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        exitEditModeOnBack.isEnabled = enabled
        activity.updateScheduleFabVisibility()
        if (!enabled) clearDragPreview()
        activity.invalidateOptionsMenu()
        activity.render()
    }

    fun confirmResetLayout() {
        AlertDialog.Builder(activity)
            .setMessage(R.string.reset_layout_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_layout) { _, _ ->
                TwidgetStore.resetDashboardCards(activity)
                activity.render()
            }
            .show()
    }

    fun removeDashboardCard(cardId: String) {
        val current = TwidgetStore.dashboardCards(activity)
        if (current.size <= 1) {
            Toast.makeText(activity, R.string.cannot_remove_last_card, Toast.LENGTH_SHORT).show()
            return
        }
        TwidgetStore.saveDashboardCards(activity, current.filterNot { it == cardId })
        activity.render()
    }

    fun showAddCardDialog() {
        val current = TwidgetStore.dashboardCards(activity)
        val hidden = TwidgetStore.DEFAULT_DASHBOARD_CARDS
            .filterNot { it in current }
            .mapNotNull(DashboardCardType::fromId)
            .filter { !it.requiresAnalyticsImport() || hasAnalyticsImport() }
        if (hidden.isEmpty()) {
            Toast.makeText(activity, R.string.all_cards_added, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.add_cards_title)
            .setItems(hidden.map { activity.getString(it.labelRes) }.toTypedArray()) { _, which ->
                TwidgetStore.saveDashboardCards(activity, current + hidden[which].id)
                activity.render()
            }
            .show()
    }

    fun previewMoveDashboardCard(draggedId: String, targetId: String) {
        val cards = (dragPreviewOrder ?: TwidgetStore.dashboardCards(activity)).toMutableList()
        val from = cards.indexOf(draggedId)
        val to = cards.indexOf(targetId)
        if (from == -1 || to == -1 || from == to) return
        val moved = cards.removeAt(from)
        cards.add(if (from < to) to - 1 else to, moved)
        if (cards == dragPreviewOrder) return
        dragPreviewOrder = cards
        DashboardCardType.fromId(draggedId)?.let { activity.dashboardBinder.moveDropPlaceholder(it, targetId) }
    }

    fun finishDashboardDrag(commit: Boolean) {
        if (draggedCardId == null) return
        if (commit) {
            dragPreviewOrder?.let { TwidgetStore.saveDashboardCards(activity, it) }
        }
        clearDragPreview()
        if (commit) activity.render()
    }

    fun clearDragPreview() {
        stopDashboardDragAutoScroll()
        dragPlaceholderView?.let { placeholder ->
            (placeholder.parent as? android.view.ViewGroup)?.removeView(placeholder)
        }
        dragSourceView?.visibility = android.view.View.VISIBLE
        draggedCardId = null
        dragPreviewOrder = null
        dragPlaceholderView = null
        dragSourceView = null
    }

    fun updateDashboardDragAutoScroll(source: View, event: DragEvent) {
        val scroll = activity.findViewById<NestedScrollView>(R.id.dashboard_scroll) ?: return
        val sourceLocation = IntArray(2).also(source::getLocationOnScreen)
        val scrollLocation = IntArray(2).also(scroll::getLocationOnScreen)
        val pointerY = sourceLocation[1] + event.y
        val edgeSize = activity.dp(AUTO_SCROLL_EDGE_DP)
        autoScrollDirection = when {
            pointerY < scrollLocation[1] + edgeSize && scroll.canScrollVertically(-1) -> -1
            pointerY > scrollLocation[1] + scroll.height - edgeSize && scroll.canScrollVertically(1) -> 1
            else -> 0
        }
        if (autoScrollDirection != 0 && !autoScrollScheduled) {
            autoScrollScheduled = true
            scroll.postOnAnimation(autoScrollStep)
        }
    }

    fun hasAnalyticsImport(): Boolean =
        ImportedAnalyticsStore.all(activity, activity.selectedAccount).isNotEmpty()

    private fun stopDashboardDragAutoScroll() {
        autoScrollDirection = 0
        activity.findViewById<NestedScrollView>(R.id.dashboard_scroll)?.removeCallbacks(autoScrollStep)
        autoScrollScheduled = false
    }

    private companion object {
        private const val AUTO_SCROLL_EDGE_DP = 72
        private const val AUTO_SCROLL_STEP_DP = 12
    }
}
