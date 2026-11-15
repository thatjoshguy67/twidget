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
    fun moveBefore(order: List<String>, card: String, before: String?): List<String> {
        if (before == null || before == card || card !in order || before !in order) return order
        return order.toMutableList().apply { remove(card); add(indexOf(before), card) }
    }
}
