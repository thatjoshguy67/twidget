package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersBrowserPolicyTest {
    private val followers = listOf(
        follower("zeta", "Zeta", 50, scanIndex = 0),
        follower("bravo", "Bravo", 100, scanIndex = 1),
        follower("alpha", "Alpha", 100, scanIndex = 2),
        follower("small", "A Small Account", 2, scanIndex = 3),
    )

    @Test
    fun sortsByFollowerCountWithStableTextTieBreakers() {
        val ranked = TopFollowersBrowserPolicy.apply(followers)

        assertEquals(listOf("alpha", "bravo", "zeta", "small"), ranked.map { it.follower.username })
        assertEquals(listOf(1, 2, 3, 4), ranked.map { it.rank })
    }

    @Test
    fun searchesDisplayNameAndUsernameIgnoringCaseAndAtPrefix() {
        assertEquals(
            listOf("small"),
            TopFollowersBrowserPolicy.apply(followers, "small account").map { it.follower.username },
        )
        assertEquals(
            listOf("bravo"),
            TopFollowersBrowserPolicy.apply(followers, "@BRAV").map { it.follower.username },
        )
    }

    @Test
    fun searchKeepsGlobalRank() {
        val result = TopFollowersBrowserPolicy.apply(followers, "zeta")

        assertEquals(1, result.size)
        assertEquals(3, result.single().rank)
    }

    @Test
    fun deDuplicatesRepeatedArchivedAccounts() {
        val duplicate = follower("zeta", "Zeta duplicate", 9_999, scanIndex = 99)

        val ranked = TopFollowersBrowserPolicy.apply(followers + duplicate)

        assertEquals(4, ranked.size)
        assertEquals(50, ranked.single { it.follower.username == "zeta" }.follower.followers)
    }

    @Test
    fun avatarRequestsOnlyStartForAttachedUnrequestedRows() {
        assertFalse(TopFollowerAvatarLoadPolicy.shouldLoad(false, "person", null))
        assertFalse(TopFollowerAvatarLoadPolicy.shouldLoad(true, null, null))
        assertFalse(TopFollowerAvatarLoadPolicy.shouldLoad(true, "person", "person"))
        assertTrue(TopFollowerAvatarLoadPolicy.shouldLoad(true, "person", null))
    }

    @Test
    fun incompleteSharedArchivesHydrateAutomatically() {
        assertTrue(TopFollowersBrowserRefreshPolicy.shouldAutoRefresh(true, 5, 274))
        assertFalse(TopFollowersBrowserRefreshPolicy.shouldAutoRefresh(true, 274, 274))
        assertFalse(TopFollowersBrowserRefreshPolicy.shouldAutoRefresh(false, 5, 274))
    }

    @Test
    fun explicitRefreshRescansWithALinkedKeyOtherwiseDownloadsFromBridge() {
        assertEquals(
            TopFollowersBrowserRefreshMode.LINKED_API_RESCAN,
            selectTopFollowersBrowserRefreshMode(linkedApiAvailable = true, shareHistory = true),
        )
        assertEquals(
            TopFollowersBrowserRefreshMode.BRIDGE_DOWNLOAD,
            selectTopFollowersBrowserRefreshMode(linkedApiAvailable = false, shareHistory = true),
        )
        assertEquals(
            TopFollowersBrowserRefreshMode.UNAVAILABLE,
            selectTopFollowersBrowserRefreshMode(linkedApiAvailable = false, shareHistory = false),
        )
    }

    private fun follower(
        username: String,
        name: String,
        count: Long,
        scanIndex: Int,
    ) = TopFollower(
        id = username,
        username = username,
        name = name,
        followers = count,
        verified = false,
        avatarUrl = "https://example.com/$username.png",
        scanIndex = scanIndex,
    )
}
