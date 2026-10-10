package com.tjg.twidget.main

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardCardReorderTest {
    private val cards = listOf("a", "b", "c", "d")

    @Test fun `adjacent half tiles can swap forward`() {
        assertEquals(listOf("b", "a", "c", "d"), reorderDashboardCards(cards, "a", "b", true))
    }

    @Test fun `moving backward supports both sides of target`() {
        assertEquals(listOf("a", "d", "b", "c"), reorderDashboardCards(cards, "d", "b", false))
        assertEquals(listOf("a", "b", "d", "c"), reorderDashboardCards(cards, "d", "b", true))
    }

    @Test fun `can move to first and last positions`() {
        assertEquals(listOf("d", "a", "b", "c"), reorderDashboardCards(cards, "d", "a", false))
        assertEquals(listOf("b", "c", "d", "a"), reorderDashboardCards(cards, "a", "d", true))
    }

    @Test fun `current slot and invalid targets preserve order`() {
        assertEquals(cards, reorderDashboardCards(cards, "a", "b", false))
        assertEquals(cards, reorderDashboardCards(cards, "a", "a", true))
        assertEquals(cards, reorderDashboardCards(cards, "missing", "a", true))
    }
}
