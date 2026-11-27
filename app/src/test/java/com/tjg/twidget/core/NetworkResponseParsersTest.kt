package com.tjg.twidget.core

import com.tjg.twidget.analytics.PostLink
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.core.json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkResponseParsersTest {
    @Test fun fxProfileRetainsTheBioForAccountConfirmation() {
        val profile = NetworkResponseParsers.parseFxTwitterUser(JSONObject("""{"user":{"screen_name":"example","description":"A profile bio"}}"""), "example")
        assertEquals("A profile bio", profile.profileDetails.bio)
        val missing = NetworkResponseParsers.parseFxTwitterUser(JSONObject("""{"user":{"screen_name":"example","description":null}}"""), "example")
        assertNull(missing.profileDetails.bio)
    }

    @Test fun sharedFieldsKeepUnknownCountersUnknownAndRetainAvailableProfileDetails() {
        val profile = NetworkResponseParsers.parseBridgeProfile("""{"userId":"123","userName":"example",
          "followersCount":0,"statusesCount":12,"likeCount":0,"bio":"About me","website":"https://example.com",
          "profileMetadata":{"fields":{"followersCount":false,"statusesCount":true,"likeCount":false},
          "expiresAt":"2026-11-25T19:00:00Z"}}""")
        assertFalse(profile.followersKnown)
        assertFalse(profile.likesKnown)
        assertTrue(profile.postsKnown)
        assertEquals("About me", profile.profileDetails.bio)
        assertEquals("https://example.com", profile.profileDetails.website)
        assertNull(profile.profileDetails.location)
        assertEquals(java.time.Instant.parse("2026-11-25T19:00:00Z").toEpochMilli(), profile.profileExpiresAt)
        val fx = NetworkResponseParsers.parseFxTwitterUser(JSONObject("""{"user":{"screen_name":"example","statuses":37}}"""), "example")
        assertTrue(fx.postsKnown)
        assertEquals(37L, fx.statusesCount)
    }

    @Test fun bridgeProfileRetainsTheSourceObservationTime() {
        val stats = NetworkResponseParsers.parseBridgeProfile("""{"userName":"example","followersCount":10,
            "profileMetadata":{"fetchedAt":"2026-11-20T12:00:00Z","cacheStatus":"hit"}}""")
        assertEquals(java.time.Instant.parse("2026-11-20T12:00:00Z").toEpochMilli(), stats.syncedAt)
    }

    @org.junit.Test fun permanentIdsAreReadWithoutMistakingPostIdsForAccountIds() {
        org.junit.Assert.assertEquals("1234567890123456789", NetworkResponseParsers.parseFxTwitterUser(
            org.json.JSONObject("""{"user":{"id":"1234567890123456789","screen_name":"renamed"}}"""), "old",
        ).userId)
        org.junit.Assert.assertEquals("123", NetworkResponseParsers.parseXApiUser(
            org.json.JSONObject("""{"data":{"id":"123","username":"renamed"}}"""), "old",
        ).userId)
        org.junit.Assert.assertEquals("456", NetworkResponseParsers.parseBridgeProfile(
            """{"user":{"userId":"456","userName":"renamed"}}""",
        ).userId)
        org.junit.Assert.assertEquals("", NetworkResponseParsers.parseBridgeProfile(
            """{"user":{"userName":"renamed","status":{"id":"999"}}}""",
        ).userId)
    }

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
              "historyUserId": "123",
              "user": {
                "userId": "123",
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
              "benchmarks": { "medianLikes": 8.0, "medianReplies": 2.0, "medianShares": 3.0 },
              "recentPosts": [
                {
                  "url": "https://x.com/example/status/2",
                  "text": "A recent original tweet",
                  "views": 2000,
                  "likes": 8,
                  "replies": 2,
                  "reposts": 1,
                  "quotes": 0,
                  "engagements": 11,
                  "ts": 2
                }
              ],
              "best": {
                "url": "https://x.com/example/status/1",
                "text": "Best https://t.co/link",
                "views": 5000,
                "likes": 10,
                "replies": 1,
                "reposts": 2,
                "quotes": 0,
                "engagements": 13,
                "ts": 1,
                "createdAt": "",
                "authorName": "",
                "authorUserName": "example",
                "authorAvatar": "javascript:alert(1)",
                "links": [
                  { "display": "https://t.co/link", "url": "https://example.com/story" },
                  { "display": "unsafe", "url": "intent://settings" }
                ],
                "media": [
                  { "type": "photo", "url": "https://cdn.example/photo.jpg", "alt": "Photo", "width": 1200, "height": 800 },
                  { "type": "photo", "url": "file:///data/local/private.jpg" }
                ]
              }
            }
            """.trimIndent(),
        )

        val analytics = NetworkResponseParsers.parseBridgeAnalytics(json)

        assertEquals("example", analytics.userName)
        assertEquals(9000L, analytics.totalViews)
        assertEquals(450L, analytics.totalEngagements)
        assertEquals(0.05, analytics.engagementRate, 0.0001)
        assertEquals(8.0, analytics.medianLikes, 0.0001)
        assertEquals(2.0, analytics.medianReplies, 0.0001)
        assertEquals(0.0, analytics.medianShares, 0.0001) // Unsupported shares from old bridge caches are ignored.
        assertEquals("Best https://t.co/link", analytics.best?.text)
        assertEquals("", analytics.best?.authorAvatar)
        assertEquals(listOf("https://example.com/story"), analytics.best?.links?.map(PostLink::url))
        assertEquals(listOf("https://cdn.example/photo.jpg"), analytics.best?.media?.map(PostMedia::url))
        assertEquals(listOf("A recent original tweet"), analytics.recentPosts.map { it.text })
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
