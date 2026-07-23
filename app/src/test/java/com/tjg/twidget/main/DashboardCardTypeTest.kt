package com.tjg.twidget.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardCardTypeTest {
    @Test
    fun `only X CSV analytics cards require an import`() {
        val importCards = setOf(
            DashboardCardType.X_IMPRESSIONS,
            DashboardCardType.X_ENGAGEMENTS,
            DashboardCardType.X_PROFILE_VISITS,
            DashboardCardType.X_LIKES_RECEIVED,
        )

        DashboardCardType.entries.forEach { card ->
            if (card in importCards) {
                assertTrue(card.requiresAnalyticsImport())
            } else {
                assertFalse(card.requiresAnalyticsImport())
            }
        }
    }
}
