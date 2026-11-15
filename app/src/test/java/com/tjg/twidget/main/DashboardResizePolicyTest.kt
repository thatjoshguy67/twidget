package com.tjg.twidget.main

import org.junit.Assert.*
import org.junit.Test

class DashboardResizePolicyTest {
    @Test fun phoneEdgesExpandInward() {
        assertEquals(setOf(DashboardResizeEdge.RIGHT), DashboardResizePolicy.edges(0, 1, 2))
        assertEquals(setOf(DashboardResizeEdge.LEFT), DashboardResizePolicy.edges(1, 1, 2))
        assertEquals(DashboardResizeEdge.entries.toSet(), DashboardResizePolicy.edges(0, 2, 2))
    }
    @Test fun fourColumnsGiveInteriorCardsBothDirections() {
        assertEquals(setOf(DashboardResizeEdge.RIGHT), DashboardResizePolicy.edges(0, 1, 4))
        assertEquals(DashboardResizeEdge.entries.toSet(), DashboardResizePolicy.edges(1, 1, 4))
        assertEquals(DashboardResizeEdge.entries.toSet(), DashboardResizePolicy.edges(2, 1, 4))
        assertEquals(setOf(DashboardResizeEdge.LEFT), DashboardResizePolicy.edges(3, 1, 4))
        for (column in 0..2) assertEquals(DashboardResizeEdge.entries.toSet(), DashboardResizePolicy.edges(column, 2, 4))
    }
    @Test fun leftExpansionMovesTheDisplacedNeighbourWithoutLosingCards() {
        val order = listOf("a", "b", "c", "d")
        val expanded = DashboardResizePolicy.moveBefore(order, "d", "c")
        assertEquals(listOf("a", "b", "d", "c"), expanded)
        assertEquals(order, DashboardResizePolicy.moveBefore(expanded, "c", "d"))
        assertEquals(order.toSet(), expanded.toSet())
        assertEquals(order, DashboardResizePolicy.moveBefore(order, "d", null))
    }
    @Test fun rtlReversesTheFlowWithoutReversingPhysicalGestureDirections() {
        assertTrue(DashboardResizePolicy.againstFlow(DashboardResizeEdge.LEFT, false))
        assertFalse(DashboardResizePolicy.againstFlow(DashboardResizeEdge.RIGHT, false))
        assertTrue(DashboardResizePolicy.againstFlow(DashboardResizeEdge.RIGHT, true))
        assertFalse(DashboardResizePolicy.againstFlow(DashboardResizeEdge.LEFT, true))
        assertEquals(-1, DashboardResizeEdge.LEFT.direction)
        assertEquals(1, DashboardResizeEdge.RIGHT.direction)
    }
}
