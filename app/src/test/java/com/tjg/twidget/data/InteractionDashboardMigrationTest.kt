package com.tjg.twidget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InteractionDashboardMigrationTest {
    @Test fun addsNewCardsAfterFollowersWithoutReorderingExistingCards() {
        val cards = listOf("milestone", "followers", "posts")
        val result = addInteractionDashboardCards(cards)
        assertEquals(listOf("milestone", "followers", "engagement_mix", "original_replies", "posts"), result)
        assertEquals(result, addInteractionDashboardCards(result))
    }
    @Test fun retiredCardsAreRemovedFromSavedLayouts() {
        assertEquals(listOf("followers", "engagement_mix"), resolveDashboardCards(
            listOf("followers", "interactions", "interaction_totals", "engagement_mix"),
            listOf("followers", "engagement_mix", "original_replies")))
    }
    @Test fun accountRenamePreservesInteractionSelectionAndMigrationMarker() {
        assertEquals("dashboard_interactions_v1:new", AccountHandleMigration.mainKey("dashboard_interactions_v1:old", "old", "new"))
        assertEquals("new:range", AccountHandleMigration.companionKey("twidget_interaction_ui", "old:range", "old", "new"))
    }
    @Test fun preservesExistingCardsWhenFollowersAreHidden() {
        assertEquals(listOf("posts", "engagement_mix", "original_replies"), addInteractionDashboardCards(listOf("posts")))
    }
}
