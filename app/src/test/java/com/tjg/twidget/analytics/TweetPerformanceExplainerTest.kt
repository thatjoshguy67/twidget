package com.tjg.twidget.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TweetPerformanceExplainerTest {
    @Test
    fun explainsStrongTweetFromOwnWeeklyBaseline() {
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 2_000, likes = 40, replies = 8, reposts = 10, quotes = 2),
            analytics(),
            TweetPerformanceDirection.STRONG,
        )
        assertTrue(explanation.body.contains("more engagements"))
        assertTrue(explanation.body.contains("more people"))
    }

    @Test
    fun explainsQuietTweetWithoutInventingACause() {
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 300, likes = 2, replies = 0, reposts = 0, quotes = 0),
            analytics(),
            TweetPerformanceDirection.QUIET,
        )
        assertTrue(explanation.body.contains("fewer people"))
        assertFalse(explanation.body.contains("because", ignoreCase = true))
        assertFalse(explanation.body.contains("shares", ignoreCase = true))
    }

    @Test
    fun likesRankAheadOfQuoteTweets() {
        val baselines = listOf(
            post(views = 300, likes = 2, replies = 100, reposts = 10, quotes = 10),
            post(views = 300, likes = 2, replies = 100, reposts = 10, quotes = 10),
        )
        val analytics = analytics().copy(
            medianViews = 300.0,
            medianEngagements = 103.0,
            medianLikes = 100.0,
            medianReplies = 100.0,
            recentPosts = baselines,
        )
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 300, likes = 2, replies = 100, reposts = 1, quotes = 0),
            analytics,
            TweetPerformanceDirection.QUIET,
        )

        assertTrue(explanation.body.contains("fewer likes"))
        assertTrue(explanation.body.contains("fewer quote tweets"))
        assertFalse(explanation.body.contains("shares", ignoreCase = true))
    }

    @Test
    fun quoteTweetsRankAheadOfRetweetsAndBothAreNamedClearly() {
        val baselines = listOf(
            post(views = 300, likes = 100, replies = 100, reposts = 10, quotes = 10),
            post(views = 300, likes = 100, replies = 100, reposts = 10, quotes = 10),
        )
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 300, likes = 100, replies = 100, reposts = 1, quotes = 1),
            analytics().copy(
                medianEngagements = 202.0,
                medianViews = 300.0,
                medianLikes = 100.0,
                medianReplies = 100.0,
                recentPosts = baselines,
            ),
            TweetPerformanceDirection.QUIET,
        )

        assertTrue(explanation.body.startsWith("It earned fewer quote tweets"))
        assertTrue(explanation.body.contains("fewer retweets"))
        assertFalse(explanation.body.contains("shares", ignoreCase = true))
        assertFalse(explanation.body.contains("reposts", ignoreCase = true))
    }

    @Test
    fun hierarchyPrefersEngagementsThenImpressions() {
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 100, likes = 1, replies = 0, reposts = 0, quotes = 0),
            analytics().copy(
                medianEngagements = 20.0,
                medianViews = 1_000.0,
                medianLikes = 10.0,
            ),
            TweetPerformanceDirection.QUIET,
        )

        assertTrue(explanation.body.startsWith("It generated fewer engagements"))
        assertTrue(explanation.body.contains("reached fewer people"))
        assertFalse(explanation.body.contains("likes"))
    }

    @Test
    fun freshTweetIsNotEligibleAsQuietest() {
        val now = 1_800_000_000_000L
        assertFalse(
            TweetPerformanceExplainer.quietTweetEligible(
                post(timestamp = now - 60 * 60 * 1000L),
                analytics(),
                now,
            ),
        )
        assertTrue(
            TweetPerformanceExplainer.quietTweetEligible(
                post(timestamp = now - 25 * 60 * 60 * 1000L),
                analytics(),
                now,
            ),
        )
    }

    private fun analytics() = PostAnalytics(
        userName = "person",
        followers = 1_000,
        postsAnalyzed = 6,
        windowDays = 7,
        totalViews = 6_000,
        avgViews = 1_000.0,
        medianViews = 1_000.0,
        avgViewsPerFollower = 1.0,
        totalEngagements = 120,
        avgEngagements = 20.0,
        medianEngagements = 20.0,
        avgEngagementsPerFollower = 0.02,
        engagementRate = 0.02,
        best = null,
        worst = null,
        cachedAt = 1L,
        medianLikes = 10.0,
        medianReplies = 2.0,
        medianShares = 2.0,
    )

    private fun post(
        views: Long = 100,
        likes: Long = 1,
        replies: Long = 1,
        reposts: Long = 1,
        quotes: Long = 0,
        timestamp: Long = 1L,
    ) = PostSummary(
        url = "https://x.com/person/status/1",
        text = "Tweet",
        views = views,
        likes = likes,
        replies = replies,
        reposts = reposts,
        quotes = quotes,
        engagements = likes + replies + reposts + quotes,
        timestamp = timestamp,
        createdAt = "",
        authorName = "Person",
        authorUserName = "person",
        authorAvatar = "",
    )
}
