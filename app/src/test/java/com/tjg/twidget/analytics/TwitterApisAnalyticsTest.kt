package com.tjg.twidget.analytics

import com.tjg.twidget.providers.TwitterApisTimelinePage
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitterApisAnalyticsTest {
    private val now = Instant.parse("2026-07-21T12:00:00Z").toEpochMilli()
    private val weekAgo = now - 7 * 24 * 60 * 60 * 1000L

    @Test
    fun `parses public metrics links media and author`() {
        val post = AnalyticsClient.parseTwitterApisPost(
            JSONObject(
                """{
                  "id":"123",
                  "text":"A useful post",
                  "createdAt":"2026-07-20T10:00:00.000Z",
                  "conversationId":"123",
                  "viewCount":1000,
                  "likeCount":40,
                  "replyCount":5,
                  "retweetCount":6,
                  "quoteCount":2,
                  "author":{"userName":"Example","name":"Example User","profilePicture":"https://pbs.twimg.com/a_normal.jpg"},
                  "entities":{"urls":[{"expanded_url":"https://example.com/story","display_url":"example.com/story"}]},
                  "media":[{"type":"photo","media_url_https":"https://pbs.twimg.com/media/photo.jpg","width":1200,"height":800,"alt_text":"A photo"}]
                }""",
            ),
            "example",
            weekAgo,
            now,
        )

        requireNotNull(post)
        assertEquals("https://x.com/Example/status/123", post.url)
        assertEquals(1_000L, post.views)
        assertEquals(53L, post.engagements)
        assertEquals("https://example.com/story", post.links.single().url)
        assertEquals("A photo", post.media.single().alt)
        assertTrue(post.authorAvatar.contains("_400x400."))
    }

    @Test
    fun `accepts snake case metrics and twitter date`() {
        val post = AnalyticsClient.parseTwitterApisPost(
            JSONObject(
                """{
                  "id":"456",
                  "created_at":"Mon Jul 20 10:00:00 +0000 2026",
                  "favorite_count":9,
                  "reply_count":3,
                  "retweet_count":2,
                  "quote_count":1,
                  "view_count":100,
                  "author":{"username":"example"}
                }""",
            ),
            "example",
            weekAgo,
            now,
        )

        requireNotNull(post)
        assertEquals(15L, post.engagements)
        assertEquals(100L, post.views)
    }

    @Test
    fun `rejects replies reposts other authors and posts outside window`() {
        val base = JSONObject(
            """{"id":"1","createdAt":"2026-07-20T10:00:00Z","author":{"userName":"example"}}""",
        )

        assertNull(parse(JSONObject(base.toString()).put("isReply", true)))
        assertNull(parse(JSONObject(base.toString()).put("isRetweet", true)))
        assertNull(parse(JSONObject(base.toString()).put("author", JSONObject().put("userName", "other"))))
        assertNull(parse(JSONObject(base.toString()).put("createdAt", "2026-07-01T10:00:00Z")))
    }

    @Test
    fun `timeline collection stops at five paid pages and reports sampling`() {
        var calls = 0

        val result = AnalyticsClient.collectTwitterApisTimeline(weekAgo) {
            calls++
            TwitterApisTimelinePage(
                tweets = listOf(tweet("$calls", now - calls * 60_000L)),
                nextCursor = "cursor-$calls",
                hasMore = true,
            )
        }

        assertEquals(5, calls)
        assertEquals(5, result.tweets.size)
        assertTrue(result.incomplete)
    }

    @Test
    fun `timeline collection stops once the seven day boundary is covered`() {
        var calls = 0

        val result = AnalyticsClient.collectTwitterApisTimeline(weekAgo) {
            calls++
            TwitterApisTimelinePage(
                tweets = listOf(tweet("old", weekAgo - 1)),
                nextCursor = "unneeded",
                hasMore = true,
            )
        }

        assertEquals(1, calls)
        assertFalse(result.incomplete)
    }

    private fun parse(tweet: JSONObject): PostSummary? =
        AnalyticsClient.parseTwitterApisPost(tweet, "example", weekAgo, now)

    private fun tweet(id: String, timestamp: Long) = JSONObject()
        .put("id", id)
        .put("timestamp", timestamp)
}
