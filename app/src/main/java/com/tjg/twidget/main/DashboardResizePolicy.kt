package com.tjg.twidget.main

internal enum class DashboardResizeEdge(val direction: Int) { LEFT(-1), RIGHT(1) }

internal object DashboardResizePolicy {
    fun edges(column: Int, span: Int, columns: Int): Set<DashboardResizeEdge> {
        if (column < 0 || span < 1 || column + span > columns) return emptySet()
        if (span > 1) return DashboardResizeEdge.entries.toSet()
        return buildSet {
            if (column > 0) add(DashboardResizeEdge.LEFT)
            if (column + span < columns) add(DashboardResizeEdge.RIGHT)
        }
    }
    fun againstFlow(edge: DashboardResizeEdge, rtl: Boolean) = (edge == DashboardResizeEdge.RIGHT) == rtl
    fun previewSpan(fraction: Float, current: Int): Int = when {
        current == 1 && fraction >= .56f -> 2
        current == 2 && fraction <= .44f -> 1
        else -> current
    }
    fun orderForResize(order: List<String>, spans: Map<String, Int>, card: String, target: Int,
        edge: DashboardResizeEdge, rtl: Boolean, columns: Int): List<String> {
        if (!againstFlow(edge, rtl) || spans[card] == target) return order
        val visible = order.mapNotNull { id -> spans[id]?.let { DashboardDragPolicy.Tile(id, it) } }
        val slots = DashboardDragPolicy.pack(visible, columns)
        val slot = slots[card] ?: return order
        val following = visible.dropWhile { it.id != card }.drop(1).firstOrNull { it.span == 1 }?.id
        if (target == 1) return moveBefore(order, following ?: return order, card)
        val collision = visible.lastOrNull { it.id != card && slots[it.id]?.let { bounds ->
            bounds.row == slot.row && bounds.column < slot.column && bounds.column + bounds.span > slot.column - 1
        } == true } ?: return order
        var changed = moveBefore(order, card, collision.id)
        if (collision.span == 2 && following != null) changed = moveBefore(changed, following, card)
        return changed
    }
    fun moveBefore(order: List<String>, card: String, before: String?): List<String> {
        if (before == null || before == card || card !in order || before !in order) return order
        return order.toMutableList().apply { remove(card); add(indexOf(before), card) }
    }
}
