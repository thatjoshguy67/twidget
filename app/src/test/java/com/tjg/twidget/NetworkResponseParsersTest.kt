package com.tjg.twidget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResponseParsersTest {
    @Test
    fun parseFxTwitterUser_readsVerificationAndProtectedFlags() {
        val json = JSONObject(
            """
            {
              "user": {
                "name": "Example User",
                "screen_name": "example",
                "followers": 1200,
                "following": 300,
                "tweets": 42,
                "likes": 900,
                "avatar_url": "https://cdn.example/avatar_normal.jpg",
                "verification": { "verified": true },
                "protected": false
              }
            }
            """.trimIndent(),
        )

        val stats = NetworkResponseParsers.parseFxTwitterUser(json, "example")

        assertEquals("Example User", stats.fullName)
        assertEquals("example", stats.userName)
        assertEquals(1200L, stats.followersCount)
        assertEquals(900L, stats.likeCount)
        assertEquals(true, stats.isVerified)
        assertEquals(false, stats.isPrivate)
        assertTrue(stats.followersKnown)
        assertTrue(stats.likesKnown)
    }

    @Test
    fun parseXApiUser_leavesLikesUnknown() {
        val json = JSONObject(
            """
            {
              "data": {
                "name": "Official",
                "username": "official",
                "verified": true,
                "verified_type": "blue",
                "protected": false,
                "profile_image_url": "https://cdn.example/normal.jpg",
                "public_metrics": {
                  "followers_count": 500,
                  "following_count": 10,
                  "tweet_count": 7
                }
              }
            }
            """.trimIndent(),
        )

        val stats = NetworkResponseParsers.parseXApiUser(json, "official")

        assertEquals(500L, stats.followersCount)
        assertEquals(0L, stats.likeCount)
        assertFalse(stats.likesKnown)
        assertEquals(true, stats.isVerified)
    }

    @Test
    fun parseBridgeProfile_supportsNestedUserObjectAndHistory() {
        val body = """
            {
              "user": {
                "fullName": "Bridge User",
                "userName": "bridgeuser",
                "followersCount": 2500,
                "followingCount": 120,
                "statusesCount": 88,
                "likeCount": 400,
                "profileImage": "https://cdn.example/avatar.jpg",
                "isVerified": true,
                "isPrivate": false
              },
              "history": [
                {
                  "dayLabel": "Jul 10",
                  "followers": 2400,
                  "following": 120,
                  "posts": 87,
                  "likes": 390,
                  "ts": 1720569600000
                }
              ]
            }
        """.trimIndent()

        val stats = NetworkResponseParsers.parseBridgeProfile(body)

        assertEquals("bridgeuser", stats.userName)
        assertEquals(2500L, stats.followersCount)
        assertEquals(1, stats.history.size)
        assertEquals(2400L, stats.history.first().followers)
    }

    @Test
    fun parseBridgeAnalyticsImport_marksSharedImportSamples() {
        val root = JSONObject(
            """
            {
              "accepted": 2,
              "checkedAnchors": 1,
              "history": [
                {
                  "dayLabel": "Jul 9",
                  "followers": 100,
                  "following": 10,
                  "posts": 1,
                  "likes": 0,
                  "ts": 1720483200000,
                  "src": "x_analytics"
                }
              ]
            }
            """.trimIndent(),
        )

        val result = NetworkResponseParsers.parseBridgeAnalyticsImport(root)

        assertEquals(2, result.accepted)
        assertEquals(1, result.checkedAnchors)
        assertEquals(1, result.history.size)
        assertTrue(result.history.first().sharedImport)
    }

    @Test
    fun parseBridgeAnalytics_readsReachAndEngagementBlocks() {
        val json = JSONObject(
            """
            {
              "userName": "example",
              "followers": 1000,
              "postsAnalyzed": 3,
              "reach": { "totalViews": 9000, "avgViews": 3000.0, "medianViews": 2500.0, "avgViewsPerFollower": 3.0 },
              "engagement": { "totalEngagements": 450, "avgEngagements": 150.0, "medianEngagements": 120.0, "avgEngagementsPerFollower": 0.15, "engagementRate": 0.05 },
              "best": { "url": "https://x.com/example/status/1", "text": "Best", "views": 5000, "likes": 10, "replies": 1, "reposts": 2, "quotes": 0, "engagements": 13, "ts": 1, "createdAt": "", "authorName": "", "authorUserName": "example", "authorAvatar": "" }
            }
            """.trimIndent(),
        )

        val analytics = NetworkResponseParsers.parseBridgeAnalytics(json)

        assertEquals("example", analytics.userName)
        assertEquals(9000L, analytics.totalViews)
        assertEquals(450L, analytics.totalEngagements)
        assertEquals(0.05, analytics.engagementRate, 0.0001)
        assertEquals("Best", analytics.best?.text)
    }

    @Test
    fun parseBridgeHistoryArray_skipsInvalidRows() {
        val array = JSONArray(
            """
            [
              { "dayLabel": "Jul 8", "followers": 50, "following": 5, "posts": 1, "likes": 0, "ts": 1720396800000 },
              { "dayLabel": "bad", "followers": 0, "following": 0, "posts": 0, "likes": 0, "ts": 0 }
            ]
            """.trimIndent(),
        )

        val history = NetworkResponseParsers.parseBridgeHistoryArray(array)

        assertEquals(1, history.size)
        assertEquals(50L, history.first().followers)
        assertFalse(history.first().sharedImport)
    }
}
