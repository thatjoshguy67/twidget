package com.tjg.twidget.banger

import com.tjg.twidget.core.json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BangerClientParseTest {
    @Test fun scoringUpgradeRescansOldResultsEvenWhenAnalyticsAreFresh() {
        assertTrue(BangerClient.needsRescan(null))
        assertTrue(BangerClient.needsRescan(JSONObject().put("complete", true)))
        assertTrue(BangerClient.needsRescan(JSONObject().put("version", 3).put("complete", true)))
        assertFalse(BangerClient.needsRescan(JSONObject().put("version", 4)))
    }

    @Test fun resultFromJson_preservesReviewContextFromTheSharedPostFormat() {
        val post = com.tjg.twidget.analytics.PostSummary("https://x.com/example/status/1", "A preview", 100, 3, 2, 1, 0, 6,
            1L, "", "Example", "example", "", fullText = "The complete reply with useful context.", isReply = true,
            quotedPost = com.tjg.twidget.analytics.QuotedPost("https://x.com/source/status/2", "The announcement", "Source", "source"))
        val result = BangerClient.resultFromJson(JSONObject().put("post", com.tjg.twidget.analytics.AnalyticsClient.serializePost(post)))
        assertEquals(post, result.post)
    }

    @Test
    fun resultFromJson_readsCompleteScanPayload() {
        val json = JSONObject(
            """
            {
              "score": 42.5,
              "complete": true,
              "postsScanned": 120,
              "capped": false,
              "post": {
                "url": "https://x.com/example/status/1",
                "text": "Hello",
                "views": 1000,
                "likes": 10,
                "replies": 2,
                "reposts": 1,
                "quotes": 0,
                "engagements": 13,
                "ts": 1,
                "createdAt": "2026-07-14",
                "authorName": "Example",
                "authorUserName": "example",
                "authorAvatar": ""
              }
            }
            """.trimIndent(),
        )

        val result = BangerClient.resultFromJson(json)

        assertEquals(42.5, result.score, 0.0001)
        assertTrue(result.complete)
        assertEquals(120, result.postsScanned)
        assertEquals("Hello", result.post?.text)
    }
}
