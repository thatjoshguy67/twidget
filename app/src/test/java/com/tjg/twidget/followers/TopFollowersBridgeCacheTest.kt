package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersBridgeCacheTest {
    @Test
    fun sharedCacheIsStrictlyOptIn() {
        assertFalse(TopFollowersSharingPolicy.enabled(false))
        assertTrue(TopFollowersSharingPolicy.enabled(true))
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

    @Test
    fun serverScanStatusAndFullPagesDecodeWithoutClientCredentials() {
        val running = TopFollowersBridgeCodec.decodeStatus("""
            {"status":"running","pages":3,"scanned":240,"startedAt":100,"updatedAt":200,"completedAt":0}
        """.trimIndent())
        requireNotNull(running)
        assertTrue(running.scanning)
        assertFalse(running.complete)
        assertEquals(240, running.scanned)

        val page = TopFollowersBridgeCodec.decodePage("""
            {
              "status":"complete","pages":4,"scanned":2,"total":2,"cachedAt":300,
              "followers":[
                {"id":"1","username":"one","name":"One","followers":20,"verified":true,"avatar":"","scanIndex":0,"mutual":true},
                {"id":"2","username":"two","name":"Two","followers":10,"verified":false,"avatar":"","scanIndex":1,"mutual":null}
              ],
              "nextOffset":null
            }
        """.trimIndent())
        requireNotNull(page)
        assertTrue(page.state.complete)
        assertEquals(300, page.state.completedAt)
        assertEquals(listOf("one", "two"), page.followers.map { it.username })
        assertEquals(true, page.followers.first().mutual)
        assertNull(page.nextOffset)
        assertNull(TopFollowersBridgeCodec.decodeStatus("{\"status\":\"queued\"}"))
    }

    @Test
    fun findsTheHighestNewFollowerEnteringTheHighRankingSet() {
        val previous = listOf(follower("existing", 50), follower("old_leader", 100))
        val current = listOf(follower("new_leader", 1_000), follower("old_leader", 100))

        val result = newHighRankingFollower(previous, current)

        requireNotNull(result)
        assertEquals("new_leader", result.follower.username)
        assertEquals(1, result.rank)
        assertNull(newHighRankingFollower(current, current))
        assertNull(newHighRankingFollower(emptyList(), current))
    }

    @Test
    fun newFollowerNotificationsIncludeRankFiftyButNotFiftyOne() {
        val previous = (1L..50L).map { rank -> follower("existing_$rank", 1_000L - rank) }
        val atFifty = previous.take(49) + follower("new_at_50", 1L)
        val atFiftyOne = previous + follower("new_at_51", 0L)

        assertEquals(50, newHighRankingFollower(previous, atFifty)?.rank)
        assertNull(newHighRankingFollower(previous, atFiftyOne))
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
