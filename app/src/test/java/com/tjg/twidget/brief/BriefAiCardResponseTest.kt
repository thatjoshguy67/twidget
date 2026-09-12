package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BriefAiCardResponseTest {
    private val source = BriefSnapshot(
        username = "account",
        generatedAt = 1L,
        sourceSyncedAt = 2L,
        analyticsCachedAt = 3L,
        followerScanCompletedAt = 4L,
        followers = 8_000,
        following = 100,
        posts = 200,
        followersToday = 2,
        followersWeek = 10,
        cards = listOf(
            BriefCard("growth", BriefCardType.GROWTH, "Growth", "You gained 10 followers.", 80),
            BriefCard("streak", BriefCardType.STREAK, "Three days", "Your streak is 3 days.", 70),
            BriefCard("steady", BriefCardType.SUMMARY, "Steady", "Everything is steady.", 60),
        ),
        topFollowerRanks = emptyMap(),
    )

    @Test
    fun compactNanoResponseRewritesCardsWithoutOverridingEngineOrder() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"streak","t":"3-day streak","b":"Your streak is 3 days."},{"i":"growth","t":"Growing","b":"You gained 10 followers."}]""",
            BriefProviderUsed.LOCAL,
        )

        assertNotNull(result.snapshot)
        assertEquals(2, result.appliedCards)
        assertEquals(listOf("growth", "streak", "steady"), result.snapshot?.cards?.map(BriefCard::id))
        assertEquals("Growing", result.snapshot?.cards?.first()?.title)
        assertEquals(BriefProviderUsed.LOCAL, result.snapshot?.providerUsed)
    }

    @Test
    fun truncatedNanoResponseReportsAParseFailure() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"growth","t":"Growing"""",
            BriefProviderUsed.LOCAL,
        )

        assertNull(result.snapshot)
        assertEquals(0, result.appliedCards)
        assertEquals("Response did not contain a complete JSON array", result.failure)
    }

    @Test
    fun changedNumericFactsFallBackToOriginalCopyButStillApplyRanking() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"growth","t":"Huge growth","b":"You gained 99 followers."}]""",
            BriefProviderUsed.LOCAL,
        )

        assertEquals("Growth", result.snapshot?.cards?.first()?.title)
        assertEquals("You gained 10 followers.", result.snapshot?.cards?.first()?.body)
        assertEquals(1, result.appliedCards)
    }

    @Test
    fun aiSummaryIsSentenceCasedAndStoredForEveryBriefSurface() {
        val result = BriefAiCardResponse.apply(
            source.copy(followersToday = 1),
            """[{"i":"__brief_summary__","t":"Your Growth Is Building","b":"Your posting rhythm is active.","s":"You gained 1 follower today and 10 this week. Your posting rhythm is active."}]""",
            BriefProviderUsed.LOCAL,
        )

        assertEquals("Your growth is building", result.snapshot?.headline)
        assertEquals("Your posting rhythm is active.", result.snapshot?.subheading)
        assertEquals(
            "You gained 1 follower today and 10 this week. Your posting rhythm is active.",
            result.snapshot?.shortDescription,
        )
        assertEquals(
            BriefEditorialSummary(
                "Your growth is building",
                "Your posting rhythm is active.",
                "You gained 1 follower today and 10 this week. Your posting rhythm is active.",
            ),
            result.snapshot?.let(BriefEditorialSummary::from),
        )
    }

    @Test
    fun inventedCompactFactsFallBackWithoutDiscardingTheAiSummary() {
        val result = BriefAiCardResponse.apply(
            source,
            """[{"i":"__brief_summary__","t":"Momentum is building","b":"Your posting rhythm is active.","s":"Up 99 followers today."}]""",
            BriefProviderUsed.LOCAL,
        )

        assertNotNull(result.snapshot)
        assertEquals(
            "You gained 2 followers today and 10 followers this week. Your posting rhythm is active.",
            result.snapshot?.shortDescription,
        )
    }
}
