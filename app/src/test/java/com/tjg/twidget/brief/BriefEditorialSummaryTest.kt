package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BriefEditorialSummaryTest {
    @Test
    fun personalGuideShapesTheOverviewWithoutDuplicatingItsCardCopy() {
        val guide = BriefCard(
            "schedule-gap",
            BriefCardType.SCHEDULE_GUIDE,
            "Plan your next tweet",
            "Nothing is scheduled for the next three days.",
            82,
        )

        val summary = BriefEditorialSummary.from(
            cards = listOf(
                BriefCard("growth", BriefCardType.GROWTH, "Growing", "Followers increased by 20.", 95),
                guide,
            ),
            followersToday = 20,
            followersWeek = 40,
        )

        assertEquals("Momentum is building", summary.title)
        assertEquals(
            "Your schedule has a useful next step waiting.",
            summary.body,
        )
        assertEquals(
            "You gained 20 followers today and 40 followers this week. " +
                "Your schedule has a useful next step.",
            summary.shortDescription,
        )
        assertFalse(summary.body.contains(guide.body))
    }

    @Test
    fun summarySynthesizesSignalsWithoutRepeatingCardCopy() {
        val cards = listOf(
            BriefCard("post", BriefCardType.POST, "Getting attention", "Your post got 100K impressions.", 98),
            BriefCard("growth", BriefCardType.GROWTH, "Growing", "Followers increased by 20.", 90),
            BriefCard("follower", BriefCardType.TOP_FOLLOWER, "New top follower", "@JohnCena is now your second most popular follower.", 80),
            BriefCard("streak", BriefCardType.STREAK, "On a roll", "Your streak is safe.", 70),
        )

        val summary = BriefEditorialSummary.from(
            cards = cards,
            followersToday = 12,
            followersWeek = 22,
        )

        assertEquals("Momentum is building", summary.title)
        assertEquals(
            "One recent tweet stood out from your usual performance. " +
                "There is a meaningful change in your top followers. Your posting rhythm is active.",
            summary.body,
        )
        assertFalse(summary.body.contains("100K impressions"))
        assertEquals(4, cards.size)
    }

    @Test
    fun summaryHasAStableEmptyFallback() {
        val summary = BriefEditorialSummary.from(emptyList())

        assertEquals("Your Twidget Brief", summary.title)
        assertEquals(
            "Twidget is watching for your next meaningful account update.",
            summary.body,
        )
        assertEquals("Watching for your next meaningful update.", summary.shortDescription)
    }

    @Test
    fun positiveFollowerMovementMakesGoalOverviewForwardLooking() {
        val cards = listOf(
            BriefCard(
                "goal",
                BriefCardType.MILESTONE,
                "Almost there",
                "Your 8,000 follower goal is within reach.",
                95,
            ),
            BriefCard(
                "post",
                BriefCardType.POST,
                "Why this tweet worked",
                "This tweet got 37K views and 330 likes.",
                90,
            ),
            BriefCard(
                "growth",
                BriefCardType.GROWTH,
                "Momentum is building",
                "You gained 12 followers today and 22 followers over the last week.",
                85,
            ),
        )

        val summary = BriefEditorialSummary.from(cards, followersToday = 12, followersWeek = 22)

        assertEquals("Moving closer", summary.title)
        assertEquals(
            "That progress brings your goal closer. " +
                "One recent tweet stood out from your usual performance.",
            summary.body,
        )
        assertFalse(summary.body.contains("37K views"))
        assertFalse(summary.body.contains("8,000 follower goal"))
        assertEquals(
            "You gained 12 followers today and 22 followers this week. " +
                "That progress brings your goal closer.",
            summary.shortDescription,
        )
    }

    @Test
    fun goalSetupEntryPointIsNotDescribedAsActiveProgress() {
        val setup = BriefCard(
            "milestone-setup",
            BriefCardType.MILESTONE,
            "Account goals",
            "Tap here to setup your account goals",
            74,
            actionData = BRIEF_MILESTONE_SETUP_ACTION,
        )

        val summary = BriefEditorialSummary.from(
            cards = listOf(setup),
            followersToday = 12,
            followersWeek = 22,
        )

        assertEquals("Momentum is building", summary.title)
        assertEquals("You gained 12 followers today and 22 followers this week.", summary.body)
    }

    @Test
    fun singularFollowerMovementUsesSingularCopy() {
        val summary = BriefEditorialSummary.from(
            cards = emptyList(),
            followersToday = 1,
            followersWeek = 1,
        )

        assertEquals("You gained 1 follower today and 1 follower this week.", summary.body)
    }
}
