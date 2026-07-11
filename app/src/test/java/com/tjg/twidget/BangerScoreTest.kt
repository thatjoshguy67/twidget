package com.tjg.twidget

import org.junit.Assert.assertTrue
import org.junit.Test

class BangerScoreTest {
    @Test
    fun balancedReachAndInteractionBeatsOneDimensionalPosts() {
        val balanced = post(views = 10_000, likes = 500, replies = 50, reposts = 50, quotes = 10)
        val impressionsOnly = post(views = 100_000, likes = 500, replies = 1, reposts = 0, quotes = 0)
        val tinyHighRate = post(views = 20, likes = 5, replies = 0, reposts = 0, quotes = 0)

        val score = BangerScore.calculate(balanced, followers = 1_000)
        assertTrue(score > BangerScore.calculate(impressionsOnly, followers = 1_000))
        assertTrue(score > BangerScore.calculate(tinyHighRate, followers = 1_000))
    }

    private fun post(views: Long, likes: Long, replies: Long, reposts: Long, quotes: Long) = PostSummary(
        url = "https://x.com/example/status/1", text = "", views = views, likes = likes,
        replies = replies, reposts = reposts, quotes = quotes,
        engagements = likes + replies + reposts + quotes, timestamp = 1, createdAt = "",
        authorName = "Example", authorUserName = "example", authorAvatar = "",
    )
}
