package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersBridgeCacheTest {
    @Test
    fun sharedCacheIsStrictlyOptInAndPublishesOnlyCompletedResults() {
        val completed = TopFollowersState(top = listOf(follower("one", 10)), complete = true)
        assertFalse(TopFollowersSharingPolicy.enabled(false))
        assertTrue(TopFollowersSharingPolicy.enabled(true))
        assertFalse(TopFollowersSharingPolicy.shouldPublish(false, completed))
        assertFalse(TopFollowersSharingPolicy.shouldPublish(true, completed.copy(complete = false)))
        assertFalse(TopFollowersSharingPolicy.shouldPublish(true, completed.copy(top = emptyList())))
        assertTrue(TopFollowersSharingPolicy.shouldPublish(true, completed))
    }

    @Test
    fun bridgeResultHydratesACompletedLocalRanking() {
        val decoded = TopFollowersBridgeCodec.decode("""
            {
              "cachedAt": 123456,
              "scanned": 250,
              "pages": 5,
              "top": [
                {"id":"1","username":"one","name":"One","followers":10,"verified":false,"avatar":""},
                {"id":"2","username":"two","name":"Two","followers":20,"verified":true,"avatar":""}
              ]
            }
        """.trimIndent())

        requireNotNull(decoded)
        assertTrue(decoded.complete)
        assertFalse(decoded.scanning)
        assertEquals(250, decoded.scanned)
        assertEquals(5, decoded.pages)
        assertEquals(123456, decoded.completedAt)
        assertEquals(listOf("two", "one"), decoded.top.map { it.username })
        assertNull(TopFollowersBridgeCodec.decode("{\"top\":[]}"))
    }

    private fun follower(username: String, followers: Long) = TopFollower(
        id = username,
        username = username,
        name = username,
        followers = followers,
        verified = false,
        avatarUrl = "",
    )
}
