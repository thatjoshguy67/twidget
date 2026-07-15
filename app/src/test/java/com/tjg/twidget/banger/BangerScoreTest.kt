package com.tjg.twidget.banger

import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BangerScoreTest {
    @Test
    fun viralHitOutranksSmallPostWithHotterEngagementRate() {
        // Regression: an 11K-view meme with a 5% like rate used to beat
        // genuinely viral posts because rate terms dominated the score.
        val nicheMeme = post(views = 11_000, likes = 574, replies = 20, reposts = 30, quotes = 5)
        val viralHit = post(views = 2_000_000, likes = 30_000, replies = 800, reposts = 5_000, quotes = 500)
        assertTrue(BangerScore.calculate(viralHit) > BangerScore.calculate(nicheMeme))
    }

    @Test
    fun substanceStillBeatsHollowReachAndTinyHighRatePosts() {
        val balanced = post(views = 10_000, likes = 500, replies = 50, reposts = 50, quotes = 10)
        val impressionsOnly = post(views = 100_000, likes = 500, replies = 1, reposts = 0, quotes = 0)
        val tinyHighRate = post(views = 20, likes = 5, replies = 0, reposts = 0, quotes = 0)

        val score = BangerScore.calculate(balanced)
        assertTrue(score > BangerScore.calculate(impressionsOnly))
        assertTrue(score > BangerScore.calculate(tinyHighRate))
    }

    @Test
    fun postsWithoutViewsOrEngagementScoreZero() {
        assertEquals(0.0, BangerScore.calculate(post(views = 0, likes = 100, replies = 0, reposts = 0, quotes = 0)), 0.0)
        assertEquals(0.0, BangerScore.calculate(post(views = 5_000, likes = 0, replies = 0, reposts = 0, quotes = 0)), 0.0)
    }

    @Test
    fun matchesBridgeScoreFormula() {
        // Must stay in lockstep with bridge/src/banger-score.js.
        val post = post(views = 11_000, likes = 574, replies = 20, reposts = 30, quotes = 5)
        val impact = 574 + 20 * 2.0 + 30 * 3.0 + 5 * 4.0
        val quality = kotlin.math.sqrt((impact / 11_100.0) / 0.02).coerceIn(0.5, 1.5)
        assertEquals(impact * quality, BangerScore.calculate(post), 1e-9)
    }

    private fun post(views: Long, likes: Long, replies: Long, reposts: Long, quotes: Long) = PostSummary(
        url = "https://x.com/example/status/1", text = "", views = views, likes = likes,
        replies = replies, reposts = reposts, quotes = quotes,
        engagements = likes + replies + reposts + quotes, timestamp = 1, createdAt = "",
        authorName = "Example", authorUserName = "example", authorAvatar = "",
    )
}
