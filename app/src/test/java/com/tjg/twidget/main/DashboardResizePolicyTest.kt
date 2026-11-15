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
    @Test fun discretePreviewsHaveABufferBetweenExpandAndShrink() {
        assertEquals(1, DashboardResizePolicy.previewSpan(.55f, 1))
        assertEquals(2, DashboardResizePolicy.previewSpan(.56f, 1))
        assertEquals(2, DashboardResizePolicy.previewSpan(.50f, 2))
        assertEquals(1, DashboardResizePolicy.previewSpan(.44f, 2))
        assertEquals(1, DashboardResizePolicy.previewSpan(.50f, 1))
    }
    @Test fun inwardExpansionKeepsTheOuterEdgeInTwoAndFourColumns() {
        for (columns in listOf(2, 4)) for (rtl in listOf(false, true)) {
            val order = (0 until columns + 2).map { "$it" }
            val id = order[columns - 1]
            val spans = order.associateWith { 1 }
            val edge = if (rtl) DashboardResizeEdge.RIGHT else DashboardResizeEdge.LEFT
            val result = DashboardResizePolicy.orderForResize(order, spans, id, 2, edge, rtl, columns)
            val packed = DashboardDragPolicy.pack(result.map { DashboardDragPolicy.Tile(it, if (it == id) 2 else 1) }, columns)
            assertEquals(columns, packed.getValue(id).column + 2)
            assertEquals(0, packed.getValue(id).row)
            assertEquals(order.toSet(), result.toSet())
        }
    }
    @Test fun mixedWideNeighboursUseAFillerWithoutLosingCards() {
        val order = listOf("wide", "resizing", "filler", "other")
        val spans = order.associateWith { if (it == "wide") 2 else 1 }
        val result = DashboardResizePolicy.orderForResize(order, spans, "resizing", 2, DashboardResizeEdge.LEFT, false, 4)
        assertEquals(listOf("filler", "resizing", "wide", "other"), result)
        val packed = DashboardDragPolicy.pack(result.map { DashboardDragPolicy.Tile(it, if (it == "wide" || it == "resizing") 2 else 1) }, 4)
        assertEquals(1, packed.getValue("resizing").column)
        assertEquals(order.toSet(), result.toSet())
    }
    @Test fun shrinkFromTheStartKeepsTheOppositeEdgeAndFlowEndPreservesOrder() {
        val order = listOf("a", "resizing", "filler", "other")
        val spans = order.associateWith { if (it == "resizing") 2 else 1 }
        val shrunk = DashboardResizePolicy.orderForResize(order, spans, "resizing", 1, DashboardResizeEdge.LEFT, false, 4)
        assertEquals(listOf("a", "filler", "resizing", "other"), shrunk)
        assertEquals(order, DashboardResizePolicy.orderForResize(order, spans, "resizing", 1, DashboardResizeEdge.RIGHT, false, 4))
        assertEquals(order, DashboardResizePolicy.orderForResize(order, spans, "resizing", 2, DashboardResizeEdge.LEFT, false, 4))
    }
}
