package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopFollowersFilterPolicyTest {
    private val followers = listOf(
        follower("c", "Charlie", 10, verified = false, scanIndex = 2, mutual = false),
        follower("a", "Alice", 100, verified = true, scanIndex = 0, mutual = true),
        follower("b", "Bob", 50, verified = true, scanIndex = 1, mutual = null),
    )

    @Test
    fun sortsAlphabetically() {
        val sorted = TopFollowersFilterPolicy.apply(followers, TopFollowersFilter.ALPHABETICAL)
        assertEquals(listOf("Alice", "Bob", "Charlie"), sorted.map { it.name })
    }

    @Test
    fun sortsRecentByScanIndexDescending() {
        val sorted = TopFollowersFilterPolicy.apply(followers, TopFollowersFilter.RECENT)
        assertEquals(listOf("Charlie", "Bob", "Alice"), sorted.map { it.name })
    }

    @Test
    fun filtersVerifiedOnly() {
        val sorted = TopFollowersFilterPolicy.apply(followers, TopFollowersFilter.VERIFIED)
        assertEquals(listOf("Alice", "Bob"), sorted.map { it.name })
    }

    @Test
    fun filtersMutualOnly() {
        val sorted = TopFollowersFilterPolicy.apply(followers, TopFollowersFilter.MUTUAL)
        assertEquals(listOf("Alice"), sorted.map { it.name })
    }

    @Test
    fun detectsMutualAvailability() {
        assertTrue(TopFollowersFilterPolicy.mutualFilterAvailable(followers))
        assertFalse(
            TopFollowersFilterPolicy.mutualFilterAvailable(
                listOf(follower("x", "X", 1, verified = false, scanIndex = 0, mutual = null)),
            ),
        )
    }

    private fun follower(
        username: String,
        name: String,
        followers: Long,
        verified: Boolean,
        scanIndex: Int,
        mutual: Boolean?,
    ) = TopFollower(
        id = username,
        username = username,
        name = name,
        followers = followers,
        verified = verified,
        avatarUrl = "",
        scanIndex = scanIndex,
        mutual = mutual,
    )
}
