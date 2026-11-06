package com.tjg.twidget.banger

import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BangerScoreTest {
    @Test fun olderHitBeatsNewerPostDespiteLowerEngagementRate() {
        val older = post(views = 253559, likes = 3663, replies = 115, reposts = 123, quotes = 48)
        val newer = post(views = 171876, likes = 3422, replies = 70, reposts = 120, quotes = 16)
        assertEquals(3961.75, BangerScore.calculate(older), 0.0)
        assertEquals(3687.5, BangerScore.calculate(newer), 0.0)
        assertTrue(BangerScore.compare(older, newer) > 0)
    }

    @Test fun missingViewsDoesNotExcludeHistoricalHit() {
        val historical = post(likes = 1000, reposts = 50)
        assertEquals(1100.0, BangerScore.calculate(historical), 0.0)
        assertTrue(BangerScore.compare(historical, post(likes = 900, views = 1000000)) > 0)
    }

    @Test fun impressionsNeverLowerTheScoreAndOnlyBreakExactTies() {
        val post = post(likes = 500, reposts = 50, replies = 20, quotes = 10)
        assertEquals(BangerScore.calculate(post.copy(views = 1000)), BangerScore.calculate(post.copy(views = 1000000)), 0.0)
        assertTrue(BangerScore.compare(post.copy(views = 2000), post.copy(views = 1000)) > 0)
        assertEquals(0, BangerScore.compare(post, post.copy(views = -1)))
        assertTrue(BangerScore.compare(post(likes = 101), post(likes = 100, views = 1000000)) > 0)
    }

    @Test fun otherEngagementSupportsLikesWithoutDominating() {
        assertEquals(106.0, BangerScore.calculate(post(likes = 100, reposts = 2, replies = 4, quotes = 2)), 0.0)
        val discussion = post(likes = 100, replies = 100000, quotes = 100000, reposts = 100000)
        assertEquals(125.0, BangerScore.calculate(discussion), 0.0)
        assertTrue(BangerScore.compare(post(likes = 126), discussion) > 0)
        assertEquals(0.0, BangerScore.calculate(post(likes = -1)), 0.0)
        assertEquals(100.0, BangerScore.calculate(post(likes = 100, replies = -100)), 0.0)
    }

    private fun post(views: Long = 0, likes: Long = 0, replies: Long = 0, reposts: Long = 0, quotes: Long = 0) = PostSummary(
        url = "https://x.com/example/status/1", text = "", views = views, likes = likes,
        replies = replies, reposts = reposts, quotes = quotes,
        engagements = likes + replies + reposts + quotes, timestamp = 1, createdAt = "",
        authorName = "Example", authorUserName = "example", authorAvatar = "",
    )
}
