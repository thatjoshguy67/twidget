package com.tjg.twidget.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardReorderPolicyTest {
    @Test
    fun `insert index is applied after dragged card is removed`() {
        assertEquals(
            listOf("a", "c", "d", "b"),
            DashboardReorderPolicy.moveCard(
                order = listOf("a", "b", "c", "d"),
                pinned = emptySet(),
                draggedId = "b",
                insertAt = 3,
            ),
        )
    }

    @Test
    fun `insert index is clamped to available slots`() {
        assertEquals(
            listOf("c", "a", "b"),
            DashboardReorderPolicy.moveCard(
                order = listOf("a", "b", "c"),
                pinned = emptySet(),
                draggedId = "c",
                insertAt = -10,
            ),
        )
        assertEquals(
            listOf("b", "c", "a"),
            DashboardReorderPolicy.moveCard(
                order = listOf("a", "b", "c"),
                pinned = emptySet(),
                draggedId = "a",
                insertAt = 99,
            ),
        )
    }

    @Test
    fun `pinned card cannot move`() {
        assertNull(
            DashboardReorderPolicy.moveCard(
                order = listOf("a", "b"),
                pinned = setOf("a"),
                draggedId = "a",
                insertAt = 1,
            ),
        )
    }
}
