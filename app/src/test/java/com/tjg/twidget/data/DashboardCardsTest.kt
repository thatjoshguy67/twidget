package com.tjg.twidget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardCardsTest {
    private val available = listOf("followers", "top_followers", "engagement")

    @Test
    fun defaultsAreUsedBeforeTheUserCustomizesTheDashboard() {
        assertEquals(available, resolveDashboardCards(saved = null, available = available))
    }

    @Test
    fun removedCardsAreNotAddedBack() {
        assertEquals(
            listOf("followers", "engagement"),
            resolveDashboardCards(
                saved = listOf("followers", "engagement"),
                available = available,
            ),
        )
    }

    @Test
    fun retiredAndDuplicateCardsAreDiscardedWithoutChangingOrder() {
        assertEquals(
            listOf("engagement", "followers"),
            resolveDashboardCards(
                saved = listOf("engagement", "retired", "followers", "engagement"),
                available = available,
            ),
        )
    }
}
