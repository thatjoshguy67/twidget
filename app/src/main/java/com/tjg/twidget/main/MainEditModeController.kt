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
import com.tjg.twidget.ui.TwidgetSnackbar

internal class MainEditModeController(
    private val activity: MainActivity,
) {
    var editMode = false
        private set

    var draggedCardId: String? = null
    var dragPreviewOrder: List<String>? = null
    private var draggedCardSpan: Int? = null

    private data class LayoutState(val account: String, val order: List<String>, val expanded: Set<String>)
    private val snackbar by lazy { TwidgetSnackbar(activity) }
    private var pendingUndo: Pair<LayoutState, LayoutState>? = null
    private var dragInitialState: LayoutState? = null
    private data class DragGeometry(val columns: Int, val peers: List<String>, val targets: List<DashboardDragPolicy.Target>)
    private var dragGeometry: DragGeometry? = null

    private fun layoutState(): LayoutState {
        val account = activity.selectedAccount
        return LayoutState(account, TwidgetStore.dashboardCards(activity, account),
            DashboardCardType.entries.filter { it.supportsSizeVariants &&
                TwidgetStore.isDashboardCardExpanded(activity, it.id, account) }.map { it.id }.toSet())
    }

    private fun forgetUndo() { pendingUndo = null; snackbar.dismiss() }

    private fun offerUndo(before: LayoutState, message: Int) {
        val after = layoutState()
        if (before == after || before.account != after.account) return
        val change = before to after
        pendingUndo = change
        snackbar.show(activity.getString(message), actionText = activity.getString(R.string.dashboard_undo)) {
            if (pendingUndo != change || draggedCardId != null || layoutState() != after) return@show
            activity.dashboardBinder.dispose()
            TwidgetStore.saveDashboardCards(activity, before.order, before.account)
            DashboardCardType.entries.filter { it.supportsSizeVariants }.forEach {
                TwidgetStore.setDashboardCardExpanded(activity, it.id, it.id in before.expanded, before.account)
            }
            forgetUndo()
            activity.render(refreshRemote = false)
            TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
        }
    }

    internal fun moveCard(card: DashboardCardType, offset: Int): Boolean {
        val order = TwidgetStore.dashboardCards(activity, activity.selectedAccount)
        val from = order.indexOf(card.id)
        val target = from + offset
        if (from < 0 || target !in order.indices) return false
        return moveCardTo(card, order[target], after = offset > 0)
    }

    private fun moveCardTo(card: DashboardCardType, target: String, after: Boolean): Boolean {
        if (!editMode || draggedCardId != null) return false
        val before = layoutState()
        val order = reorderDashboardCards(before.order, card.id, target, after)
        if (order == before.order) return false
        forgetUndo()
        activity.dashboardBinder.dispose()
        TwidgetStore.saveDashboardCards(activity, order, before.account)
        activity.render(refreshRemote = false)
        activity.findViewById<View>(R.id.dashboard_content).post {
            activity.findViewById<android.view.ViewGroup>(R.id.dashboard_content).findViewWithTag<View>(card.id)?.requestFocus()
        }
        TwidgetHaptics.selection(activity.findViewById(R.id.dashboard_content))
        offerUndo(before, R.string.dashboard_card_moved)
        return true
    }

    internal fun showMoveCardDialog(card: DashboardCardType): Boolean {
        if (!editMode || draggedCardId != null) return false
        val account = activity.selectedAccount
        val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
        val visible = (0 until grid.childCount).map { grid.getChildAt(it).tag }.toSet()
        val peers = TwidgetStore.dashboardCards(activity, account).filter { it != card.id && it in visible }
            .mapNotNull(DashboardCardType::fromId)
        if (peers.isEmpty()) return false
        val labels = peers.map { activity.getString(R.string.dashboard_before_card, activity.getString(it.labelRes)) } +
            activity.getString(R.string.dashboard_after_card, activity.getString(peers.last().labelRes))
        AlertDialog.Builder(activity).setTitle(R.string.dashboard_move_to).setItems(labels.toTypedArray()) { _, index ->
            if (activity.selectedAccount == account) {
                val target = peers.getOrNull(index) ?: peers.last()
                moveCardTo(card, target.id, after = index == peers.size)
            }
        }.setNegativeButton(R.string.cancel, null).show()
        return true
    }

    // Background refreshes must not replace the live drag grid. A changed
    // profile/layout cancels the provisional state before the new page binds.
    fun onDashboardBinding(account: String): Boolean {
        if (pendingUndo?.second?.account?.let { it != account } == true) forgetUndo()
        val original = dragInitialState ?: return true
        if (original.account == account && layoutState() == original) return false
        clearDragPreview()
        return true
    }

    fun beginDashboardDrag(card: DashboardCardType, source: View?, span: Int = card.span(activity, activity.selectedAccount)) {
        forgetUndo()
        dragInitialState = layoutState()
        dragGeometry = null
        draggedCardId = card.id
        draggedCardSpan = span
        dragPreviewOrder = dragInitialState!!.order.let { if (card.id in it) it else it + card.id }
        dragSourceView = source
        dropHover.clear()
    }

    internal fun restoreState(enabled: Boolean) {
        editMode = enabled
        exitEditModeOnBack.isEnabled = enabled
    }

    fun cardSpan(card: DashboardCardType): Int =
        if (card.id == draggedCardId) draggedCardSpan ?: card.span(activity, activity.selectedAccount) else card.span(activity, activity.selectedAccount)
    var dragPlaceholderView: android.view.View? = null
    var dragSourceView: android.view.View? = null

    private val dropHover = DashboardDropHover()
    private var hoverView: View? = null
    private val matureHover = Runnable { updateDropTarget(dragPointerX, dragPointerY) }

    private fun clearHover() {
        dropHover.clear()
        hoverView?.removeCallbacks(matureHover)
        hoverView = null
    }

    val dashboardDragListener = View.OnDragListener { source, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> editMode && draggedCardId != null &&
                (event.localState == draggedCardId || event.clipDescription?.label?.toString() == "dashboard_card")
            DragEvent.ACTION_DRAG_LOCATION -> {
                updateDashboardDragAutoScroll(source, event)
                updateDropTarget(dragPointerX, dragPointerY)
                true
            }
            DragEvent.ACTION_DROP -> {
                // Commit exactly the visible preview, never jump to a pending
                // destination that the user has not seen on screen yet.
                finishDashboardDrag(commit = true)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                finishDashboardDrag(commit = false)
                true
            }
            else -> true
        }
    }

    private fun updateDropTarget(x: Float, y: Float) {
        val dragged = draggedCardId ?: return
        if (activity.selectedAccount != dragInitialState?.account) { finishDashboardDrag(false); return }
        val grid = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        val order = dragPreviewOrder ?: return
        val rowHeight = activity.dp(160) + activity.dp(7) * 2
        fun tile(card: DashboardCardType) = DashboardDragPolicy.Tile(card.id, cardSpan(card),
            (activity.dp(card.preferredHeightDp) + activity.dp(7) * 2).toFloat() / rowHeight)
        // Cache packing for the drag, rather than allocating every projected
        // arrangement on each pointer frame. A column-count change rebuilds it.
        val geometry = dragGeometry?.takeIf { it.columns == grid.columnCount } ?: run {
            val visible = (0 until grid.childCount).mapNotNull { grid.getChildAt(it).tag as? String }.toSet() + dragged
            val peers = order.filter { it != dragged && it in visible }.mapNotNull { id ->
                DashboardCardType.fromId(id)?.let(::tile)
            }
            val draggedTile = DashboardCardType.fromId(dragged)?.let(::tile) ?: return
            DragGeometry(grid.columnCount, peers.map { it.id }, DashboardDragPolicy.targets(peers, draggedTile, grid.columnCount))
                .also { dragGeometry = it }
        }
        val active = order.filter { it == dragged || it in geometry.peers }.indexOf(dragged)
        val origin = IntArray(2).also(grid::getLocationOnScreen)
        val columnWidth = (grid.width - grid.paddingLeft - grid.paddingRight).toFloat() / grid.columnCount
        if (columnWidth <= 0) return
        val physicalX = (x - origin[0] - grid.paddingLeft) / columnWidth
        val logicalX = if (grid.layoutDirection == View.LAYOUT_DIRECTION_RTL) grid.columnCount - physicalX else physicalX
        val logicalY = (y - origin[1] - grid.paddingTop) / rowHeight
        if (logicalX < -.5f || logicalX > grid.columnCount + .5f || logicalY < -.5f) { clearHover(); return }
        val target = DashboardDragPolicy.nearest(geometry.targets, logicalX, logicalY, active)
        val now = SystemClock.uptimeMillis()
        val ready = dropHover.update(target?.index, active, now)
        hoverView?.removeCallbacks(matureHover)
        hoverView = grid
        if (ready != null) {
            val before = geometry.peers.getOrNull(ready)
            val anchor = before ?: geometry.peers.lastOrNull()
            if (anchor != null) previewMoveDashboardCard(dragged, anchor, after = before == null)
            dropHover.clear()
        } else dropHover.remaining(now)?.let { grid.postDelayed(matureHover, it) }
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
            updateDropTarget(dragPointerX, dragPointerY)
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
        if (!enabled) {
            activity.dashboardBinder.dispose()
            clearDragPreview()
        }
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
                forgetUndo()
                TwidgetStore.resetDashboardCards(activity, activity.selectedAccount)
                TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
                activity.render()
            }
            .show()
    }

    fun removeDashboardCard(cardId: String) {
        val current = TwidgetStore.dashboardCards(activity, activity.selectedAccount)
        if (current.size <= 1) {
            TwidgetHaptics.reject(activity.findViewById(R.id.dashboard_content))
            Toast.makeText(activity, R.string.cannot_remove_last_card, Toast.LENGTH_SHORT).show()
            return
        }
        if (draggedCardId != null || cardId !in current) return
        val before = layoutState()
        forgetUndo()
        activity.dashboardBinder.dispose()
        TwidgetStore.saveDashboardCards(activity, current.filterNot { it == cardId }, before.account)
        TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
        activity.render(refreshRemote = false)
        offerUndo(before, R.string.dashboard_card_removed)
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

    fun startDrawerCardDrag(card: DashboardCardType, source: View, preview: View, span: Int = card.span(activity, activity.selectedAccount)): Boolean {
        val current = TwidgetStore.dashboardCards(activity, activity.selectedAccount)
        if (draggedCardId != null || card !in availableDashboardCards() || card.id in current) return false
        if (!editMode) setEditMode(true)
        // Keep the new card provisional until it lands on the dashboard.
        beginDashboardDrag(card, source = null, span = span)
        activity.dashboardBinder.moveDropPlaceholder(card, current.lastOrNull().orEmpty(), after = true)
        source.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) finishDashboardDrag(commit = false)
            true
        }
        val started = source.startDragAndDrop(android.content.ClipData.newPlainText("dashboard_card", card.id),
            View.DragShadowBuilder(preview), card.id, View.DRAG_FLAG_GLOBAL)
        if (started) {
            TwidgetHaptics.dragPickup(source)
        } else finishDashboardDrag(commit = false)
        return started
    }

    fun addDashboardCard(card: DashboardCardType, span: Int = card.span(activity, activity.selectedAccount)) {
        val current = TwidgetStore.dashboardCards(activity, activity.selectedAccount)
        if (card !in availableDashboardCards() || card.id in current) return
        val before = layoutState()
        forgetUndo()
        if (card.supportsSizeVariants) TwidgetStore.setDashboardCardExpanded(activity, card.id, span == 2, activity.selectedAccount)
        TwidgetStore.saveDashboardCards(activity, current + card.id, activity.selectedAccount)
        TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
        activity.render(refreshRemote = false)
        offerUndo(before, R.string.dashboard_card_added)
    }

    fun resizeDashboardCard(card: DashboardCardType) {
        if (!editMode || !card.supportsSizeVariants || draggedCardId != null) return
        forgetUndo()
        activity.dashboardBinder.resizeCard(card)
    }

    fun previewMoveDashboardCard(draggedId: String, targetId: String, after: Boolean) {
        val cards = (dragPreviewOrder ?: TwidgetStore.dashboardCards(activity, activity.selectedAccount)).toMutableList()
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
        val before = dragInitialState
        val valid = commit && before != null && layoutState() == before
        if (valid) {
            dragPreviewOrder?.let { order ->
                DashboardCardType.fromId(droppedId)?.takeIf { it.supportsSizeVariants }?.let { card ->
                    draggedCardSpan?.let { TwidgetStore.setDashboardCardExpanded(activity, card.id, it == 2, before!!.account) }
                }
                TwidgetStore.saveDashboardCards(activity, order, before!!.account)
                TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
            }
        }
        clearDragPreview()
        activity.render(refreshRemote = false)
        if (valid) {
            activity.dashboardBinder.showDropBounce(droppedId)
            offerUndo(before!!, if (droppedId in before.order) R.string.dashboard_card_moved else R.string.dashboard_card_added)
        }
    }

    fun clearDragPreview() {
        activity.dashboardBinder.dispose()
        clearHover()
        stopDashboardDragAutoScroll()
        dragPlaceholderView?.let { placeholder ->
            (placeholder.parent as? android.view.ViewGroup)?.removeView(placeholder)
        }
        dragSourceView?.visibility = View.VISIBLE
        draggedCardId = null
        draggedCardSpan = null
        dragPreviewOrder = null
        dragInitialState = null
        dragGeometry = null
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
