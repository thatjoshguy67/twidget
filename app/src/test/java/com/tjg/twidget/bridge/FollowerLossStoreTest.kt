package com.tjg.twidget.bridge

import org.junit.Assert.*
import org.junit.Test

class FollowerLossStoreTest {
    private val row = """{"userId":"123","from":1000,"to":2000,"lostFollowers":3,"source":"complete_follower_scans","semantics":"observed_membership_changes_not_daily_unfollow_events"}"""
    @Test fun scanLossesStayIntervalsAndRequireMatchingAccountIdentity() {
        val body = """{"userId":"123","followerChanges":[$row]}"""
        assertEquals(listOf(FollowerLossObservation(1000, 2000, 3)), FollowerLossStore.decode(body, "123"))
        assertNull(FollowerLossStore.decode(body, "999"))
        assertNull(FollowerLossStore.decode(body, ""))
        for (invalid in listOf(row.replace("\"123\"", "\"999\""), row.replace(":3", ":-1"),
            row.replace(":2000", ":500"), row.replace("complete_follower_scans", "estimate"))) {
            assertTrue(FollowerLossStore.decode("""{"userId":"123","followerChanges":[$invalid]}""", "123")!!.isEmpty())
        }
        assertTrue(FollowerLossStore.decode("""{"userId":"123"}""", "123")!!.isEmpty())
    }
}
