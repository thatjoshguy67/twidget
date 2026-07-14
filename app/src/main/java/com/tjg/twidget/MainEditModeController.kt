package com.tjg.twidget

import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog

internal class MainEditModeController(
    private val activity: MainActivity,
) {
    var editMode = false
        private set

    var draggedCardId: String? = null
    var dragPreviewOrder: List<String>? = null
    var dragPlaceholderView: android.view.View? = null
    var dragSourceView: android.view.View? = null

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
        dragPlaceholderView?.let { placeholder ->
            (placeholder.parent as? android.view.ViewGroup)?.removeView(placeholder)
        }
        dragSourceView?.visibility = android.view.View.VISIBLE
        draggedCardId = null
        dragPreviewOrder = null
        dragPlaceholderView = null
        dragSourceView = null
    }
}
