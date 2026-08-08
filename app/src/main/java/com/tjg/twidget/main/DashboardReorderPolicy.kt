package com.tjg.twidget.main

object DashboardReorderPolicy {
    fun moveCard(
        order: List<String>,
        pinned: Set<String>,
        draggedId: String,
        insertAt: Int,
    ): List<String>? {
        if (draggedId in pinned) return null
        val from = order.indexOf(draggedId)
        if (from == -1) return null

        val next = order.toMutableList()
        next.removeAt(from)
        next.add(insertAt.coerceIn(0, next.size), draggedId)
        return next
    }
}
