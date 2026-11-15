package com.tjg.twidget.main

import org.junit.Assert.*
import org.junit.Test

class DashboardDragPolicyTest {
    private fun tile(id: String, span: Int = 1) = DashboardDragPolicy.Tile(id, span)

    @Test fun `wide cards have side by side destinations in four columns`() {
        val targets = DashboardDragPolicy.targets(listOf(tile("graph", 2), tile("stat")), tile("tweet", 2), 4)
        assertEquals(DashboardDragPolicy.Slot(0, 0, 2), targets[0].slot)
        assertEquals(DashboardDragPolicy.Slot(0, 2, 2), targets[1].slot)
        assertEquals(1, DashboardDragPolicy.nearest(targets, 3f, .5f, 0)!!.index)
    }

    @Test fun `narrow cards can move either side of a wide card on the same row`() {
        val targets = DashboardDragPolicy.targets(listOf(tile("graph", 2)), tile("stat"), 4)
        assertEquals(DashboardDragPolicy.Slot(0, 0, 1), targets[0].slot)
        assertEquals(DashboardDragPolicy.Slot(0, 2, 1), targets[1].slot)
        assertEquals(1, DashboardDragPolicy.nearest(targets, 2.5f, .5f, 0)!!.index)
    }

    @Test fun `wide cards wrap instead of changing size in two columns`() {
        val targets = DashboardDragPolicy.targets(listOf(tile("a"), tile("b")), tile("graph", 2), 2)
        assertEquals(DashboardDragPolicy.Slot(0, 0, 2), targets[0].slot)
        assertEquals(DashboardDragPolicy.Slot(1, 0, 2), targets[1].slot)
        assertEquals(targets[1].slot, targets[2].slot)
        // An ambiguous destination fills the preceding row instead of creating a gap.
        assertEquals(2, DashboardDragPolicy.nearest(targets, 1f, 1.5f, 0)!!.index)
    }

    @Test fun `the current slot wins coincident targets and small boundary jitter`() {
        val targets = DashboardDragPolicy.targets(listOf(tile("a"), tile("b")), tile("graph", 2), 2)
        assertEquals(1, DashboardDragPolicy.nearest(targets, 1f, 1.5f, 1)!!.index)
        val narrow = DashboardDragPolicy.targets(listOf(tile("a"), tile("b"), tile("c")), tile("moving"), 4)
        for (x in listOf(.99f, 1.04f, 1.08f, 1.01f))
            assertEquals(0, DashboardDragPolicy.nearest(narrow, x, .5f, 0)!!.index)
        assertEquals(1, DashboardDragPolicy.nearest(narrow, 1.25f, .5f, 0)!!.index)
    }

    @Test fun `every mixed layout preserves spans relative order and nonoverlapping slots`() {
        for (columns in listOf(2, 4)) for (mask in 0 until 64) {
            val peers = (0..5).map { tile("$it", if (mask and (1 shl it) == 0) 1 else 2) }
            for (span in listOf(1, 2)) {
                val dragged = tile("moving", span)
                val targets = DashboardDragPolicy.targets(peers, dragged, columns)
                assertEquals(peers.size + 1, targets.size)
                targets.forEach { target ->
                    val order = peers.toMutableList().apply { add(target.index, dragged) }
                    val slots = DashboardDragPolicy.pack(order, columns)
                    assertEquals(peers, order.filter { it.id != dragged.id })
                    assertEquals(span, slots.getValue(dragged.id).span)
                    assertEquals(target.slot, slots.getValue(dragged.id))
                    val occupied = mutableSetOf<Pair<Int, Int>>()
                    slots.values.forEach { slot ->
                        assertTrue(slot.column >= 0 && slot.column + slot.span <= columns)
                        repeat(slot.span) { assertTrue(occupied.add(slot.row to slot.column + it)) }
                    }
                }
            }
        }
    }

    @Test fun `empty grids and below last row have valid destinations`() {
        val empty = DashboardDragPolicy.targets(emptyList(), tile("only", 2), 4)
        assertEquals(0, DashboardDragPolicy.nearest(empty, 3f, 6f, 0)!!.index)
        val targets = DashboardDragPolicy.targets(listOf(tile("a"), tile("b")), tile("moving"), 2)
        assertEquals(2, DashboardDragPolicy.nearest(targets, .5f, 100f, 0)!!.index)
    }

    @Test fun `a stationary hover matures and crossing back cancels the pending move`() {
        val hover = DashboardDropHover()
        assertNull(hover.update(2, 0, 1000))
        assertEquals(160L, hover.remaining(1000))
        assertNull(hover.update(2, 0, 1159))
        assertEquals(2, hover.update(2, 0, 1160))
        assertNull(hover.update(0, 0, 1161))
        assertNull(hover.remaining(1161))
    }

    @Test fun `changing hover targets resets the dwell and cancellation clears it`() {
        val hover = DashboardDropHover()
        assertNull(hover.update(1, 0, 0))
        assertNull(hover.update(2, 0, 150))
        assertNull(hover.update(2, 0, 160))
        assertEquals(150L, hover.remaining(160))
        hover.clear()
        assertNull(hover.remaining(1000))
        assertNull(hover.update(2, 0, 1000))
        assertEquals(2, hover.update(2, 0, 1160))
    }
}
