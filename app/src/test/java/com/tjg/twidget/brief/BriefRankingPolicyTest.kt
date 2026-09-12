package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.main.MilestonePerformanceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BriefRankingPolicyTest {
    @Test
    fun urgentGuideCanOutrankAHighBasePriorityGoal() {
        val cards = listOf(
            BriefCard(
                "milestone",
                BriefCardType.MILESTONE,
                "Goal",
                "",
                99,
                rankSignals = BriefRankSignals(contextRelevance = 0.55, timeRelevance = 0.45),
            ),
            BriefCard(
                "schedule",
                BriefCardType.SCHEDULE_GUIDE,
                "Fix it",
                "",
                90,
                action = BriefCardAction.OPEN_SCHEDULER,
                rankSignals = BriefRankSignals(contextRelevance = 1.0, timeRelevance = 1.0),
            ),
        )

        assertEquals("schedule", BriefRankingPolicy.order(cards, contextAt(9)).first().id)
    }

    @Test
    fun rankingRetainsEveryRelevantCard() {
        val cards = BriefCardType.entries.take(7).mapIndexed { index, type ->
            BriefCard("card-$index", type, "Card $index", "", 100 - index)
        }

        assertEquals(cards.size, BriefRankingPolicy.order(cards).size)
    }

    @Test
    fun rankingFiltersCardsPastTheirValidityWindow() {
        val now = contextAt(12).now
        val cards = listOf(
            BriefCard("current", BriefCardType.POSTING_GUIDE, "Current", "", 70),
            BriefCard(
                "expired",
                BriefCardType.POSTING_GUIDE,
                "Expired",
                "",
                100,
                rankSignals = BriefRankSignals(validUntil = now - 1),
            ),
        )

        assertEquals(listOf("current"), BriefRankingPolicy.order(cards, BriefRankingPolicy.Context(now)).map(BriefCard::id))
    }

    @Test
    fun freshnessChangesTheOrderOfOtherwiseEqualCards() {
        val now = contextAt(14).now
        val day = 24 * 60 * 60 * 1000L
        val cards = listOf(
            BriefCard(
                "older",
                BriefCardType.POST,
                "Older",
                "",
                85,
                rankSignals = BriefRankSignals(occurredAt = now - 6 * day, freshForMillis = 7 * day, timeRelevance = 0.2),
            ),
            BriefCard(
                "recent",
                BriefCardType.POST,
                "Recent",
                "",
                85,
                rankSignals = BriefRankSignals(occurredAt = now - day, freshForMillis = 7 * day, timeRelevance = 0.2),
            ),
        )

        assertEquals("recent", BriefRankingPolicy.order(cards, BriefRankingPolicy.Context(now)).first().id)
    }

    @Test
    fun relatedCardsAreSpreadAcrossTheBriefWhenScoresAreClose() {
        val cards = listOf(
            BriefCard("growth", BriefCardType.GROWTH, "Growth", "", 95),
            BriefCard("goal", BriefCardType.MILESTONE, "Goal", "", 94),
            BriefCard("learn", BriefCardType.POST, "Learn", "", 90),
        )

        assertEquals(
            listOf("growth", "learn", "goal"),
            BriefRankingPolicy.order(cards, contextAt(9)).map(BriefCard::id),
        )
    }

    @Test
    fun calculatedRankingScoreIsExposedForDebugging() {
        val ranked = BriefRankingPolicy.order(
            listOf(BriefCard("growth", BriefCardType.GROWTH, "Growth", "", 80)),
            contextAt(9),
        ).single()

        assertTrue(ranked.rankingScore in 0..100)
    }

    @Test
    fun accountActivityChangesGrowthPriority() {
        val quiet = BriefRankingPolicy.growth(today = 5, week = 15, weeklyPercent = 2.0)
        val surging = BriefRankingPolicy.growth(today = 25, week = 80, weeklyPercent = 8.0)

        assertTrue(surging > quiet)
    }

    @Test
    fun standoutPostIsRelativeToAccountSize() {
        val post = PostSummary(
            url = "",
            text = "",
            views = 20_000,
            likes = 150,
            replies = 0,
            reposts = 0,
            quotes = 0,
            engagements = 150,
            timestamp = 0,
            createdAt = "",
            authorName = "",
            authorUserName = "",
            authorAvatar = "",
        )

        assertTrue(
            BriefRankingPolicy.post(post, followers = 1_000) >
                BriefRankingPolicy.post(post, followers = 100_000),
        )
    }

    @Test
    fun urgentStreakOutranksAnAlreadySafeStreak() {
        assertTrue(
            BriefRankingPolicy.streak(days = 10, activeToday = false) >
                BriefRankingPolicy.streak(days = 10, activeToday = true),
        )
    }

    @Test
    fun nearbyOrSlippingGoalGetsMorePriority() {
        val early = BriefRankingPolicy.milestone(25, MilestonePerformanceState.NEUTRAL)
        val close = BriefRankingPolicy.milestone(90, MilestonePerformanceState.NEUTRAL)
        val slipping = BriefRankingPolicy.milestone(90, MilestonePerformanceState.DECELERATING)

        assertTrue(close > early)
        assertTrue(slipping > close)
    }

    private fun contextAt(hour: Int): BriefRankingPolicy.Context {
        val now = LocalDate.of(2026, 8, 2)
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return BriefRankingPolicy.Context(now)
    }
}
