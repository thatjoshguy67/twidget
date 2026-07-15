package com.tjg.twidget.banger

import com.tjg.twidget.schedule.json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BangerClientParseTest {
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
