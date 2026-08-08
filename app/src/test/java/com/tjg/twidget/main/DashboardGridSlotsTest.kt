package com.tjg.twidget.main

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardGridSlotsTest {
    private val mixedCards = listOf(
        DashboardGridSlots.CardBounds("left", 0f, 0f, 100f, 100f, 1),
        DashboardGridSlots.CardBounds("right", 100f, 0f, 200f, 100f, 1),
        DashboardGridSlots.CardBounds("full", 0f, 100f, 200f, 220f, 2),
    )

    @Test
    fun `half-width cards resolve horizontal insertion slots`() {
        assertEquals(
            0,
            DashboardGridSlots.resolveInsertIndex(mixedCards, 5f, 50f, 1, 0f),
        )
        assertEquals(
            1,
            DashboardGridSlots.resolveInsertIndex(mixedCards, 105f, 50f, 0, 0f),
        )
    }

    @Test
    fun `full-width cards resolve vertical insertion slots`() {
        assertEquals(
            2,
            DashboardGridSlots.resolveInsertIndex(mixedCards, 100f, 105f, 1, 0f),
        )
        assertEquals(
            3,
            DashboardGridSlots.resolveInsertIndex(mixedCards, 100f, 215f, 2, 0f),
        )
    }

    @Test
    fun `hysteresis keeps current slot near boundary`() {
        assertEquals(
            1,
            DashboardGridSlots.resolveInsertIndex(mixedCards, 98f, 50f, 1, 16f),
        )
    }

    @Test
    fun `tall full-width card uses boundary below it without horizontal bias`() {
        val cards = listOf(
            DashboardGridSlots.CardBounds("tall", 0f, 0f, 200f, 400f, 2),
            DashboardGridSlots.CardBounds("next", 0f, 400f, 100f, 500f, 1),
        )

        assertEquals(
            1,
            DashboardGridSlots.resolveInsertIndex(cards, 100f, 390f, 0, 0f),
        )
    }

    @Test
    fun `bottom gutter appends after tall post card`() {
        val cards = listOf(
            DashboardGridSlots.CardBounds("post", 0f, 0f, 200f, 360f, 2),
        )

        assertEquals(
            1,
            DashboardGridSlots.resolveInsertIndex(
                cards,
                pointerX = 100f,
                pointerY = 340f,
                currentInsertAt = 0,
                hysteresisPx = 12f,
                bottomGutterPx = 24f,
            ),
        )
    }

    @Test
    fun `bottom gutter appends below mixed half width row`() {
        val cards = listOf(
            DashboardGridSlots.CardBounds("full", 0f, 0f, 200f, 160f, 2),
            DashboardGridSlots.CardBounds("left", 0f, 160f, 100f, 300f, 1),
            DashboardGridSlots.CardBounds("right", 100f, 160f, 200f, 300f, 1),
        )

        assertEquals(
            3,
            DashboardGridSlots.resolveInsertIndex(
                cards,
                pointerX = 20f,
                pointerY = 285f,
                currentInsertAt = 1,
                hysteresisPx = 12f,
                bottomGutterPx = 24f,
            ),
        )
    }
}
