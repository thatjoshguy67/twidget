package com.tjg.twidget.analytics

import com.tjg.twidget.brief.TestBriefStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TweetPerformanceExplainerTest {
    private val strings = TestBriefStrings()
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun explainsStrongTweetFromOwnWeeklyBaseline() {
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 2_000, likes = 40, replies = 8, reposts = 10, quotes = 2),
            analytics(),
            TweetPerformanceDirection.STRONG,
            strings,
        )
        assertTrue(explanation.body.contains("more engagements"))
        assertTrue(explanation.body.contains("more views"))
    }

    @Test
    fun explainsQuietTweetWithoutInventingACause() {
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 300, likes = 2, replies = 0, reposts = 0, quotes = 0),
            analytics(),
            TweetPerformanceDirection.QUIET,
            strings,
        )
        assertTrue(explanation.body.contains("fewer views"))
        assertFalse(explanation.body.contains("because", ignoreCase = true))
        assertFalse(explanation.body.contains("shares", ignoreCase = true))
    }

    @Test
    fun likesRankAheadOfQuoteTweets() {
        val analytics = analytics().copy(recentPosts = listOf(
            post(views = 300, likes = 100, replies = 0, reposts = 10, quotes = 10, id = "a"),
            post(views = 300, likes = 100, replies = 0, reposts = 10, quotes = 10, id = "b"),
            post(views = 300, likes = 2, replies = 100, reposts = 1, quotes = 0),
        ))
        val explanation = TweetPerformanceExplainer.explain(
            post(views = 300, likes = 2, replies = 100, reposts = 1, quotes = 0),
            analytics,
            TweetPerformanceDirection.QUIET,
            strings,
        )

        assertTrue(explanation.body.contains("fewer likes"))
        assertTrue(explanation.body.contains("fewer quote tweets"))
        assertFalse(explanation.body.contains("shares", ignoreCase = true))
    }

    @Test
    fun quoteTweetsRankAheadOfRetweetsAndBothAreNamedClearly() {
        val target = post(views = 300, likes = 100, replies = 100, reposts = 1, quotes = 1)
        val explanation = TweetPerformanceExplainer.explain(
            target,
            analytics().copy(recentPosts = listOf(
                target,
                post(views = 300, likes = 100, replies = 100, reposts = 10, quotes = 10, id = "a"),
                post(views = 300, likes = 100, replies = 100, reposts = 10, quotes = 10, id = "b"),
            )),
            TweetPerformanceDirection.QUIET,
            strings,
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
            strings,
        )

        assertTrue(explanation.body.startsWith("It generated fewer engagements"))
        assertTrue(explanation.body.contains("recorded fewer views"))
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
                post(timestamp = now - 48 * hour),
                analytics(),
                now,
            ),
        )
    }

    @Test
    fun doesNotCompareTwoHourCountsWithSixDayCounts() {
        val target = post(timestamp = now - 2 * hour)
        val data = analytics().copy(recentPosts = listOf(
            target,
            post(timestamp = now - 6 * 24 * hour, id = "a"),
            post(timestamp = now - 6 * 24 * hour, id = "b"),
        ))
        val explanation = TweetPerformanceExplainer.explain(target, data, TweetPerformanceDirection.QUIET, strings)
        assertTrue(explanation.body.contains("fair comparison"))
        assertEquals(1, explanation.confidence)
        assertFalse(TweetPerformanceExplainer.quietTweetEligible(target, data, now))
    }

    @Test
    fun cachedFreshPostDoesNotMatureAsTheClockAdvances() {
        val target = post(timestamp = now - 2 * hour)
        val data = analytics().copy(recentPosts = listOf(
            target,
            post(timestamp = now - 2 * hour, views = 1_000, id = "a"),
            post(timestamp = now - 2 * hour, views = 1_000, id = "b"),
        ))
        assertFalse(TweetPerformanceExplainer.quietTweetEligible(target, data, now + 3 * 24 * hour))
    }

    @Test
    fun legacyAggregatesAndReplyOnlyWinnersDoNotBecomeAgeBaselines() {
        val target = post(id = "reply")
        assertEquals(null, PostAgeComparison.baseline(target, analytics()))
        assertEquals(null, PostAgeComparison.baseline(post(), analytics().copy(recentPosts = emptyList())))
    }

    @Test
    fun subjectAndDuplicatePeersCannotSupplyTheMinimumSample() {
        val target = post()
        val peer = post(id = "a")
        assertEquals(null, PostAgeComparison.baseline(target, analytics().copy(recentPosts = listOf(target, target, peer, peer))))
    }

    @Test
    fun rejectsMissingAndFutureTimestampsButAllowsHistoricalObservations() {
        for (timestamp in listOf(0L, now + hour)) {
            assertEquals(null, PostAgeComparison.age(post(timestamp = timestamp), analytics()))
        }
        assertEquals(8 * 24 * hour, PostAgeComparison.age(post(timestamp = now - 8 * 24 * hour), analytics()))
    }

    @Test
    fun quietAdviceRequiresObservedUnderperformanceNotJustWeeklyRank() {
        val target = post(views = 1_000, likes = 100)
        val data = analytics().copy(recentPosts = analytics().recentPosts + target.copy(url = "other"))
        assertFalse(TweetPerformanceExplainer.quietTweetEligible(target, data, now))
    }

    @Test
    fun zeroBaselineDoesNotInventARatioAndSampledDataCapsConfidence() {
        val target = post(views = 1_000, likes = 100)
        val zeros = (1..6).map { post(views = 0, likes = 0, replies = 0, reposts = 0, id = "zero$it") }
        val data = analytics().copy(recentPosts = zeros + target)
        assertEquals(null, PostAgeComparison.baseline(target, data)?.ratio(target, PostSummary::views))
        val sampled = analytics().copy(isSampled = true)
        assertEquals(1, TweetPerformanceExplainer.explain(target, sampled, TweetPerformanceDirection.STRONG, strings).confidence)
    }

    @Test
    fun briefSelectsMatureQuietPostEvenWhenRawWeeklyWorstIsNew() {
        val fresh = post(views = 1, likes = 0, replies = 0, reposts = 0, timestamp = now - hour, id = "fresh")
        val quiet = post(views = 100, likes = 1, replies = 0, reposts = 0)
        val data = analytics().copy(worst = fresh, recentPosts = analytics().recentPosts + fresh)
        val card = com.tjg.twidget.brief.BriefEngine.worstPostCard(data, strings, now)
        assertEquals("worst-post-${quiet.timestamp}", card?.id)
        assertTrue(card?.body.orEmpty().contains("similar age"))
    }

    @Test
    fun ageCohortIgnoresPerformanceAndRetainsUsefulPeersDespiteAnOutlier() {
        val posts = listOf(
            post(timestamp = now - 48 * hour, id = "a"),
            post(timestamp = now - 50 * hour, id = "b"),
            post(timestamp = now - 60 * hour, id = "c"),
            post(timestamp = now - 72 * hour, id = "d"),
            post(timestamp = now - 6 * 24 * hour, views = 1_000_000, id = "old"),
        )
        val cohort = PostAgeComparison.cohort(posts, analytics())
        assertEquals(listOf("a", "b", "c", "d"), cohort.map { it.url.substringAfterLast('/') })
    }

    @Test
    fun confidenceUsesDistinctAgePeersRatherThanWeeklyPostCount() {
        val target = post(views = 2_000, likes = 100)
        val peers = (1..5).map { post(views = 1_000, likes = 10, id = "peer$it") }
        val data = analytics().copy(recentPosts = peers + target, postsAnalyzed = 100)
        assertEquals(3, TweetPerformanceExplainer.explain(target, data, TweetPerformanceDirection.STRONG, strings).confidence)
        assertEquals(2, TweetPerformanceExplainer.explain(target, data.copy(recentPosts = peers.take(2) + target), TweetPerformanceDirection.STRONG, strings).confidence)
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
        cachedAt = now,
        recentPosts = listOf(
            post(views = 100, likes = 1, replies = 0, reposts = 0),
            post(views = 1_000, likes = 10, replies = 2, reposts = 5, quotes = 3, id = "a"),
            post(views = 1_000, likes = 10, replies = 2, reposts = 5, quotes = 3, id = "b"),
        ),
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
        timestamp: Long = now - 48 * hour,
        id: String = "1",
    ) = PostSummary(
        url = "https://x.com/person/status/$id",
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
