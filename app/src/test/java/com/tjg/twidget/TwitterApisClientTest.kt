package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitterApisClientTest {
    @Test
    fun parsesProfileMetrics() {
        val profile = TwitterApisClient.parseProfile(
            """{"data":{"username":"person","name":"Person","followers_count":9000,"following_count":25,"tweet_count":300,"favourites_count":40,"profile_image_url":"https://pbs.twimg.com/a_normal.jpg","is_blue_verified":true,"protected":false}}""",
            "fallback",
        )

        assertEquals("person", profile.userName)
        assertEquals(9_000L, profile.followersCount)
        assertEquals(25L, profile.followingsCount)
        assertEquals(300L, profile.statusesCount)
        assertEquals(40L, profile.likeCount)
        assertTrue(profile.isVerified == true)
        assertTrue(profile.isPrivate == false)
        assertTrue(profile.profileImage.contains("_400x400."))
    }

    @Test
    fun parsesNestedGraphProfileShape() {
        val profile = TwitterApisClient.parseProfile(
            """{"data":{"user":{"result":{"legacy":{"screen_name":"nested","name":"Nested User","followers_count":77,"friends_count":8,"statuses_count":9,"favourites_count":10,"profile_image_url_https":"https://pbs.twimg.com/n_normal.jpg","verified":false,"protected":true}}}}}""",
            "fallback",
        )

        assertEquals("nested", profile.userName)
        assertEquals(77L, profile.followersCount)
        assertEquals(8L, profile.followingsCount)
        assertTrue(profile.isPrivate == true)
    }

    @Test
    fun parsesFollowerPageAndCursor() {
        val page = TwitterApisClient.parsePage(
            """{
              "users": [{
                "id": "42",
                "username": "famous",
                "name": "Famous Person",
                "followers_count": 1234567,
                "following_count": 12,
                "is_blue_verified": true,
                "profile_image_url": "https://pbs.twimg.com/profile_images/42/avatar_normal.jpg"
              }],
              "next_cursor": "cursor-value"
            }""",
        )

        assertEquals("cursor-value", page.nextCursor)
        assertEquals(1, page.users.size)
        assertEquals("famous", page.users.single().username)
        assertEquals(1_234_567L, page.users.single().followers)
        assertTrue(page.users.single().verified)
        assertTrue(page.users.single().avatarUrl.contains("_400x400."))
    }

    @Test
    fun emptyUsersIsReliableCompletionSignal() {
        val page = TwitterApisClient.parsePage("""{"users":[],"next_cursor":"still-present"}""")
        assertTrue(page.users.isEmpty())
        assertEquals("still-present", page.nextCursor)
    }

    @Test
    fun rankingKeepsLargestUniqueAccounts() {
        val users = listOf(
            follower("1", "small", 10),
            follower("2", "largest", 1_000),
            follower("1", "duplicate", 999),
            follower("3", "middle", 100),
        )

        val ranked = rankedTopFollowers(users, limit = 2)

        assertEquals(listOf("largest", "middle"), ranked.map { it.username })
        assertFalse(ranked.any { it.username == "duplicate" })
    }

    private fun follower(id: String, username: String, followers: Long) = TopFollower(
        id = id,
        username = username,
        name = username,
        followers = followers,
        verified = false,
        avatarUrl = "",
    )
}
