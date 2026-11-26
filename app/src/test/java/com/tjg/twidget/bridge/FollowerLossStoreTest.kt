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
    @Test fun dailyChartUsesOnlyShortConsecutiveDayIntervals() {
        val zone = java.time.ZoneOffset.UTC
        val from = java.time.Instant.parse("2026-11-25T20:00:00Z").toEpochMilli()
        val to = java.time.Instant.parse("2026-11-26T11:00:00Z").toEpochMilli()
        val valid = FollowerLossObservation(from, to, 4)
        val result = FollowerLossStore.dailyObservations(listOf(valid,
            FollowerLossObservation(from - 7 * 86_400_000L, to, 100),
            FollowerLossObservation(from, from + 1000, 2),
            FollowerLossObservation(to, to + 86_400_000L, 3)), zone, to)
        assertEquals(mapOf(java.time.LocalDate.of(2026, 11, 26) to valid), result)
    }
}
