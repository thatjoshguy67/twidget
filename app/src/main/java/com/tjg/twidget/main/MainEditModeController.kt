package com.tjg.twidget.main

import android.view.DragEvent
import android.view.View
import android.os.SystemClock
import android.widget.GridLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetHaptics

internal class MainEditModeController(
    private val activity: MainActivity,
) {
    var editMode = false
        private set

    var draggedCardId: String? = null
    var dragPreviewOrder: List<String>? = null
    var dragPlaceholderView: android.view.View? = null
    var dragSourceView: android.view.View? = null

    private var holdFeedbackView: View? = null
    private val holdFeedback = object : Runnable {
        override fun run() {
            val view = holdFeedbackView ?: return
            if (draggedCardId == null || !view.isAttachedToWindow || !view.hasWindowFocus()) {
                stopDragHoldFeedback()
                return
            }
            TwidgetHaptics.dragHold(view)
            view.postDelayed(this, 120L)
        }
    }

    fun startDragHoldFeedback() {
        stopDragHoldFeedback()
        holdFeedbackView = activity.findViewById(R.id.dashboard_content)
        holdFeedbackView?.postDelayed(holdFeedback, 120L)
    }

    private fun stopDragHoldFeedback() {
        holdFeedbackView?.removeCallbacks(holdFeedback)
        holdFeedbackView = null
    }

    private var hoverTarget: Pair<String, Boolean>? = null
    private var hoverStartedAt = 0L

    val dashboardDragListener = View.OnDragListener { source, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> editMode && draggedCardId != null &&
                (event.localState == draggedCardId || event.clipDescription?.label?.toString() == "dashboard_card")
            DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DROP -> {
                updateDashboardDragAutoScroll(source, event)
                updateDropTarget(dragPointerX, dragPointerY, immediate = event.action == DragEvent.ACTION_DROP)
                if (event.action == DragEvent.ACTION_DROP) finishDashboardDrag(commit = true)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                finishDashboardDrag(commit = false)
                true
            }
            else -> true
        }
    }

    private fun updateDropTarget(x: Float, y: Float, immediate: Boolean) {
        val dragged = draggedCardId ?: return
        val grid = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        val gridOrigin = IntArray(2).also(grid::getLocationOnScreen)
        val margin = activity.dp(5)
        var candidate: Pair<String, Boolean>? = null
        for (index in 0 until grid.childCount) {
            val child = grid.getChildAt(index)
            if (child.visibility != View.VISIBLE || child === dragPlaceholderView) continue
            val id = child.tag as? String ?: continue
            val card = DashboardCardType.fromId(id) ?: continue
            if (id == dragged) continue
            // Target settled slots rather than the animated visual positions.
            val location = intArrayOf(gridOrigin[0] + child.left, gridOrigin[1] + child.top)
            if (x < location[0] - margin || x > location[0] + child.width + margin ||
                y < location[1] - margin || y > location[1] + child.height + margin) continue
            // Half-width tiles use left/right zones; full-width tiles use top/bottom.
            val after = if (card.size.span == 1) {
                val right = x > location[0] + child.width / 2f
                if (grid.layoutDirection == View.LAYOUT_DIRECTION_RTL) !right else right
            } else y > location[1] + child.height / 2f
            candidate = id to after
            break
        }
        val now = SystemClock.uptimeMillis()
        if (candidate != hoverTarget) {
            hoverTarget = candidate
            hoverStartedAt = now
        }
        candidate?.let { (id, after) ->
            if (immediate || now - hoverStartedAt >= 140L) {
                previewMoveDashboardCard(dragged, id, after)
            }
        }
    }

    private var dragPointerX = 0f
    private var dragPointerY = 0f
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
            val viewport = android.graphics.Rect()
            if (!scroll.getGlobalVisibleRect(viewport)) return
            val edge = activity.dp(AUTO_SCROLL_EDGE_DP).toFloat()
            val proximity = if (autoScrollDirection < 0) {
                (viewport.top + edge - dragPointerY) / edge
            } else (dragPointerY - viewport.bottom + edge) / edge
            val step = (activity.dp(AUTO_SCROLL_STEP_DP) * proximity.coerceIn(0.2f, 1f)).toInt().coerceAtLeast(1)
            scroll.scrollBy(0, step * autoScrollDirection)
            updateDropTarget(dragPointerX, dragPointerY, immediate = false)
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
        activity.dashboardBinder.animateEditModeChange(enabled, onTransitionStart = {
            val grid = activity.findViewById<android.view.View>(R.id.dashboard_content)
            TwidgetHaptics.editModePop(grid, entering = enabled)
        }) { activity.render() }
    }

    fun confirmResetLayout() {
        AlertDialog.Builder(activity)
            .setMessage(R.string.reset_layout_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_layout) { _, _ ->
                TwidgetStore.resetDashboardCards(activity)
                TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
                activity.render()
            }
            .show()
    }

    fun removeDashboardCard(cardId: String) {
        val current = TwidgetStore.dashboardCards(activity)
        if (current.size <= 1) {
            TwidgetHaptics.reject(activity.findViewById(R.id.dashboard_content))
            Toast.makeText(activity, R.string.cannot_remove_last_card, Toast.LENGTH_SHORT).show()
            return
        }
        TwidgetStore.saveDashboardCards(activity, current.filterNot { it == cardId })
        TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
        activity.render()
    }

    fun showAddCardDialog() {
        MainAddCardDrawer(activity, this).show()
    }

    fun availableDashboardCards(): List<DashboardCardType> = TwidgetStore.DEFAULT_DASHBOARD_CARDS
        .mapNotNull(DashboardCardType::fromId)
        .filter { !it.requiresAnalyticsImport() || hasAnalyticsImport() }
        .filter {
            it != DashboardCardType.MILESTONE || activity.selectedAccount.equals(
                TwidgetStore.settings(activity).username, ignoreCase = true,
            )
        }

    fun startDrawerCardDrag(card: DashboardCardType, source: View, preview: View): Boolean {
        val current = TwidgetStore.dashboardCards(activity)
        if (draggedCardId != null || card !in availableDashboardCards() || card.id in current) return false
        if (!editMode) setEditMode(true)
        draggedCardId = card.id
        // Keep the new card provisional until it lands on the dashboard.
        dragPreviewOrder = current + card.id
        dragSourceView = null
        activity.dashboardBinder.moveDropPlaceholder(card, current.lastOrNull().orEmpty(), after = true)
        source.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) finishDashboardDrag(commit = false)
            true
        }
        val started = source.startDragAndDrop(android.content.ClipData.newPlainText("dashboard_card", card.id),
            View.DragShadowBuilder(preview), card.id, View.DRAG_FLAG_GLOBAL)
        if (started) {
            TwidgetHaptics.dragPickup(source)
            startDragHoldFeedback()
        } else finishDashboardDrag(commit = false)
        return started
    }

    fun addDashboardCard(card: DashboardCardType) {
        val current = TwidgetStore.dashboardCards(activity)
        if (card !in availableDashboardCards() || card.id in current) return
        TwidgetStore.saveDashboardCards(activity, current + card.id)
        TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
        activity.render()
    }

    fun previewMoveDashboardCard(draggedId: String, targetId: String, after: Boolean) {
        val cards = (dragPreviewOrder ?: TwidgetStore.dashboardCards(activity)).toMutableList()
        val from = cards.indexOf(draggedId)
        val to = cards.indexOf(targetId)
        if (from == -1 || to == -1 || from == to) return
        val reordered = reorderDashboardCards(cards, draggedId, targetId, after)
        cards.clear()
        cards.addAll(reordered)
        if (cards == dragPreviewOrder) return
        dragPreviewOrder = cards
        TwidgetHaptics.selection(activity.findViewById(R.id.dashboard_content))
        DashboardCardType.fromId(draggedId)?.let { activity.dashboardBinder.moveDropPlaceholder(it, targetId, after) }
    }

    fun finishDashboardDrag(commit: Boolean) {
        val droppedId = draggedCardId ?: return
        if (commit) {
            dragPreviewOrder?.let { order ->
                val changed = order != TwidgetStore.dashboardCards(activity)
                TwidgetStore.saveDashboardCards(activity, order)
                if (changed) TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
            }
        }
        clearDragPreview()
        activity.render()
        if (commit) activity.dashboardBinder.showDropBounce(droppedId)
    }

    fun clearDragPreview() {
        stopDragHoldFeedback()
        hoverTarget = null
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
        dragPointerX = sourceLocation[0] + event.x
        dragPointerY = sourceLocation[1] + event.y
        val viewport = android.graphics.Rect()
        if (!scroll.getGlobalVisibleRect(viewport)) return
        val edgeSize = activity.dp(AUTO_SCROLL_EDGE_DP)
        autoScrollDirection = when {
            dragPointerY < viewport.top + edgeSize && scroll.canScrollVertically(-1) -> -1
            dragPointerY > viewport.bottom - edgeSize && scroll.canScrollVertically(1) -> 1
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
        private const val AUTO_SCROLL_EDGE_DP = 96
        private const val AUTO_SCROLL_STEP_DP = 10
    }
}

internal fun reorderDashboardCards(cards: List<String>, draggedId: String, targetId: String, after: Boolean): List<String> {
    if (draggedId == targetId || draggedId !in cards || targetId !in cards) return cards
    return cards.toMutableList().apply {
        remove(draggedId)
        add(indexOf(targetId) + if (after) 1 else 0, draggedId)
    }
}
