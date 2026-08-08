package com.tjg.twidget.main

import android.view.DragEvent
import android.view.View
import android.widget.GridLayout
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
    var dragInsertAt: Int = -1
        private set

    var dragSourceHeight: Int = 0
        private set

    var dragSourceLayoutParams: android.view.ViewGroup.LayoutParams? = null
        private set

    var dragSourceDetached = false
        private set

    private var dragFinishInFlight = false

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
            val stepDp = if (
                autoScrollDirection > 0 &&
                dragInsertAt >= (dragPreviewOrder?.lastIndex ?: Int.MAX_VALUE)
            ) {
                AUTO_SCROLL_BOTTOM_STEP_DP
            } else {
                AUTO_SCROLL_STEP_DP
            }
            scroll.scrollBy(0, activity.dp(stepDp) * autoScrollDirection)
            activity.dashboardBinder.scheduleRefreshDashboardDragLocation()
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

    fun enterEditModeForDrag() {
        if (editMode) return
        editMode = true
        exitEditModeOnBack.isEnabled = true
        activity.updateScheduleFabVisibility()
        activity.invalidateOptionsMenu()
        activity.render(bindDashboard = false)
        activity.dashboardBinder.applyEditModeVisuals(animate = false)
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

    fun beginDashboardDrag(draggedId: String, source: View) {
        val cards = TwidgetStore.dashboardCards(activity)
        val card = DashboardCardType.fromId(draggedId)
        draggedCardId = draggedId
        dragPreviewOrder = cards
        dragSourceView = source
        dragInsertAt = cards.indexOf(draggedId).coerceAtLeast(0)
        dragSourceHeight = source.height.takeIf { it > 0 }
            ?: card?.let { activity.dp(it.size.heightDp) }
            ?: 0
        activity.dashboardBinder.beginDashboardDragSession()
        activity.dashboardBinder.suspendDashboardLayoutTransition()
        activity.dashboardBinder.detachDragSourceIfNeeded(source)
        activity.dashboardBinder.showDashboardBottomDropZone(dragSourceHeight)
        card?.let {
            activity.dashboardBinder.applyPreviewOrder(it, cards, dragInsertAt)
        }
    }

    fun previewMoveDashboardCard(draggedId: String, insertAt: Int) {
        val pinned = emptySet<String>()
        val cards = dragPreviewOrder ?: TwidgetStore.dashboardCards(activity)
        val next = DashboardReorderPolicy.moveCard(cards, pinned, draggedId, insertAt) ?: return
        if (next == dragPreviewOrder && insertAt == dragInsertAt) return
        dragPreviewOrder = next
        dragInsertAt = insertAt
        DashboardCardType.fromId(draggedId)?.let {
            activity.dashboardBinder.schedulePreviewOrder(it, next, insertAt)
        }
        dragSourceView?.performHapticFeedback(
            if (insertAt >= cards.lastIndex) {
                android.view.HapticFeedbackConstants.CONFIRM
            } else {
                android.view.HapticFeedbackConstants.CLOCK_TICK
            },
        )
    }

    fun finishDashboardDrag(commit: Boolean) {
        if (draggedCardId == null || dragFinishInFlight) return
        dragFinishInFlight = true
        val shouldCommit = commit
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content)
        val finish = Runnable {
            if (shouldCommit) {
                dragPreviewOrder?.let { order ->
                    TwidgetStore.saveDashboardCards(activity, order)
                    activity.dashboardBinder.settleDashboardDrag(order)
                }
            }
            clearDragPreview()
            dragFinishInFlight = false
        }
        if (container != null) {
            container.post(finish)
        } else {
            finish.run()
        }
    }

    fun clearDragPreview() {
        stopDashboardDragAutoScroll()
        activity.dashboardBinder.cancelScheduledPreviewOrder()
        activity.dashboardBinder.restoreDragSourceOnCancel()
        activity.dashboardBinder.hideDashboardBottomDropZone()
        activity.dashboardBinder.restoreDashboardLayoutTransition()
        dragPlaceholderView?.let { placeholder ->
            (placeholder.parent as? android.view.ViewGroup)?.removeView(placeholder)
        }
        draggedCardId = null
        dragPreviewOrder = null
        dragPlaceholderView = null
        dragSourceView = null
        dragInsertAt = -1
        dragSourceHeight = 0
        dragSourceLayoutParams = null
        dragSourceDetached = false
    }

    internal fun markDragSourceDetached(layoutParams: android.view.ViewGroup.LayoutParams) {
        dragSourceLayoutParams = layoutParams
        dragSourceDetached = true
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
        private const val AUTO_SCROLL_BOTTOM_STEP_DP = 16
    }
}
