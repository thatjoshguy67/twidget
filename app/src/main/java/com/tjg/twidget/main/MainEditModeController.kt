package com.tjg.twidget.main

import android.view.DragEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetHaptics
import com.tjg.twidget.ui.TwidgetSnackbar

/** Profile-scoped transactions. RecyclerView owns all provisional visual movement. */
internal class MainEditModeController(private val activity: MainActivity) {
    var editMode = false
        private set
    var draggedCardId: String? = null
        private set
    var dragPreviewOrder: List<String>? = null
        private set
    private var draggedCardSpan: Int? = null
    private data class LayoutState(val account: String, val order: List<String>, val expanded: Set<String>)
    private val snackbar by lazy { TwidgetSnackbar(activity) }
    private var pendingUndo: Pair<LayoutState, LayoutState>? = null
    private var dragInitialState: LayoutState? = null
    private var resizeInitialState: LayoutState? = null
    private val interacting get() = draggedCardId != null || resizeInitialState != null

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
            if (pendingUndo != change || interacting || layoutState() != after) return@show
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
        val order = activity.dashboardBinder.cardIds()
        val from = order.indexOf(card.id)
        val target = from + offset
        if (from < 0 || target !in order.indices) return false
        return moveCardTo(card, order[target], after = offset > 0)
    }
    private fun moveCardTo(card: DashboardCardType, target: String, after: Boolean): Boolean {
        if (!editMode || interacting) return false
        val before = layoutState()
        val order = reorderDashboardCards(before.order, card.id, target, after)
        if (order == before.order) return false
        forgetUndo()
        TwidgetStore.saveDashboardCards(activity, order, before.account)
        activity.render(refreshRemote = false)
        activity.dashboardBinder.scrollToCard(card.id)
        activity.findViewById<View>(R.id.dashboard_content).post {
            activity.findViewById<android.view.ViewGroup>(R.id.dashboard_content).findViewWithTag<View>(card.id)?.requestFocus()
        }
        TwidgetHaptics.selection(activity.findViewById(R.id.dashboard_content))
        offerUndo(before, R.string.dashboard_card_moved)
        return true
    }
    internal fun showMoveCardDialog(card: DashboardCardType): Boolean {
        if (!editMode || interacting) return false
        val account = activity.selectedAccount
        val peers = activity.dashboardBinder.cardIds().filter { it != card.id }.mapNotNull(DashboardCardType::fromId)
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
    fun onDashboardBinding(account: String): Boolean {
        if (pendingUndo?.second?.account?.let { it != account } == true) forgetUndo()
        val original = dragInitialState ?: resizeInitialState ?: return true
        if (original.account == account && layoutState() == original) return false
        cancelInteractions()
        return true
    }
    fun beginDashboardDrag(card: DashboardCardType, source: View?, span: Int = card.span(activity, activity.selectedAccount)) {
        forgetUndo()
        dragInitialState = layoutState()
        draggedCardId = card.id
        draggedCardSpan = span
        dragPreviewOrder = dragInitialState!!.order.let { if (card.id in it) it else it + card.id }
    }
    fun previewMoveDashboardCard(draggedId: String, targetId: String, after: Boolean) {
        if (draggedCardId != draggedId) return
        val order = dragPreviewOrder ?: return
        val next = reorderDashboardCards(order, draggedId, targetId, after)
        if (next == order) return
        dragPreviewOrder = next
        activity.dashboardBinder.applyCardOrder(next)
        TwidgetHaptics.selection(activity.findViewById(R.id.dashboard_content))
    }
    fun finishDashboardDrag(commit: Boolean) {
        val id = draggedCardId ?: return
        val before = dragInitialState ?: return
        val valid = commit && layoutState() == before
        val order = if (valid) dragPreviewOrder ?: before.order else layoutState().order
        if (valid) {
            if (id !in before.order && DashboardCardType.fromId(id)?.supportsSizeVariants == true)
                TwidgetStore.setDashboardCardExpanded(activity, id, draggedCardSpan == 2, before.account)
            TwidgetStore.saveDashboardCards(activity, order, before.account)
        }
        draggedCardId = null
        draggedCardSpan = null
        dragPreviewOrder = null
        dragInitialState = null
        activity.dashboardBinder.finishCardDrag(order)
        if (valid) {
            TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
            offerUndo(before, if (id in before.order) R.string.dashboard_card_moved else R.string.dashboard_card_added)
        }
    }
    fun beginResize(): Boolean {
        if (!editMode || interacting) return false
        forgetUndo()
        resizeInitialState = layoutState()
        return true
    }
    fun finishResize(card: DashboardCardType, span: Int, order: List<String>, commit: Boolean) {
        val before = resizeInitialState ?: return
        val valid = commit && layoutState() == before
        resizeInitialState = null
        if (valid) {
            TwidgetStore.setDashboardCardExpanded(activity, card.id, span == 2, before.account)
            TwidgetStore.saveDashboardCards(activity, order, before.account)
        }
        activity.dashboardBinder.finishCardResize(card, if (valid) order else layoutState().order)
        if (valid) {
            TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
            offerUndo(before, R.string.dashboard_card_resized)
        }
    }
    fun cancelInteractions() {
        finishDashboardDrag(false)
        activity.dashboardBinder.dispose()
    }
    fun cardSpan(card: DashboardCardType): Int = activity.dashboardBinder.previewSpan(card)
    internal fun restoreState(enabled: Boolean) { editMode = enabled; exitEditModeOnBack.isEnabled = enabled }
    val exitEditModeOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { setEditMode(false) }
    }
    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        if (!enabled) cancelInteractions()
        editMode = enabled
        exitEditModeOnBack.isEnabled = enabled
        activity.invalidateOptionsMenu()
        activity.dashboardBinder.animateEditModeChange(enabled, onTransitionStart = {
            TwidgetHaptics.editModePop(activity.findViewById(R.id.dashboard_content), entering = enabled)
        }) { activity.render(refreshRemote = false) }
    }
    fun confirmResetLayout() {
        AlertDialog.Builder(activity).setMessage(R.string.reset_layout_confirm)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.reset_layout) { _, _ ->
                cancelInteractions(); forgetUndo()
                TwidgetStore.resetDashboardCards(activity, activity.selectedAccount)
                TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
                activity.render(refreshRemote = false)
            }.show()
    }
    fun removeDashboardCard(cardId: String) {
        if (interacting) return
        val before = layoutState()
        if (before.order.size <= 1) {
            TwidgetHaptics.reject(activity.findViewById(R.id.dashboard_content))
            Toast.makeText(activity, R.string.cannot_remove_last_card, Toast.LENGTH_SHORT).show()
            return
        }
        if (cardId !in before.order) return
        forgetUndo()
        TwidgetStore.saveDashboardCards(activity, before.order.filterNot { it == cardId }, before.account)
        TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
        activity.render(refreshRemote = false)
        offerUndo(before, R.string.dashboard_card_removed)
    }
    fun showAddCardDialog() { MainAddCardDrawer(activity, this).show() }
    fun availableDashboardCards(): List<DashboardCardType> = TwidgetStore.DEFAULT_DASHBOARD_CARDS
        .mapNotNull(DashboardCardType::fromId)
        .filter { !it.requiresAnalyticsImport() || hasAnalyticsImport() }
        .filter { it != DashboardCardType.MILESTONE || activity.selectedAccount.equals(TwidgetStore.settings(activity).username, ignoreCase = true) }
    fun startDrawerCardDrag(card: DashboardCardType, source: View, preview: View, span: Int = card.span(activity, activity.selectedAccount)): Boolean {
        val current = TwidgetStore.dashboardCards(activity, activity.selectedAccount)
        if (interacting || card !in availableDashboardCards() || card.id in current) return false
        if (!editMode) setEditMode(true)
        beginDashboardDrag(card, null, span)
        activity.dashboardBinder.showExternalCard(card, span, dragPreviewOrder!!)
        source.setOnDragListener { _, event ->
            if (event.action == DragEvent.ACTION_DRAG_ENDED) finishDashboardDrag(false)
            true
        }
        val started = source.startDragAndDrop(android.content.ClipData.newPlainText("dashboard_card", card.id),
            View.DragShadowBuilder(preview), card.id, View.DRAG_FLAG_GLOBAL)
        if (started) TwidgetHaptics.dragPickup(source) else finishDashboardDrag(false)
        return started
    }
    fun addDashboardCard(card: DashboardCardType, span: Int = card.span(activity, activity.selectedAccount)) {
        if (interacting) return
        val before = layoutState()
        if (card !in availableDashboardCards() || card.id in before.order) return
        forgetUndo()
        if (card.supportsSizeVariants) TwidgetStore.setDashboardCardExpanded(activity, card.id, span == 2, before.account)
        TwidgetStore.saveDashboardCards(activity, before.order + card.id, before.account)
        TwidgetHaptics.confirm(activity.findViewById(R.id.dashboard_content))
        activity.render(refreshRemote = false)
        offerUndo(before, R.string.dashboard_card_added)
    }
    fun resizeDashboardCard(card: DashboardCardType) {
        if (!editMode || interacting || !card.supportsSizeVariants) return
        activity.dashboardBinder.resizeCard(card)
    }
    fun hasAnalyticsImport() = ImportedAnalyticsStore.all(activity, activity.selectedAccount).isNotEmpty()
}

internal fun reorderDashboardCards(cards: List<String>, draggedId: String, targetId: String, after: Boolean): List<String> {
    if (draggedId == targetId || draggedId !in cards || targetId !in cards) return cards
    return cards.toMutableList().apply { remove(draggedId); add(indexOf(targetId) + if (after) 1 else 0, draggedId) }
}
