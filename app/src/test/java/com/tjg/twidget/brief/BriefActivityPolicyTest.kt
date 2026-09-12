package com.tjg.twidget.brief

import com.tjg.twidget.data.StreakSnapshot
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefActivityPolicyTest {
    private val today = LocalDate.of(2026, 8, 1)

    @Test
    fun doesNotPromptAtExactlyThreeMissedCalendarDays() {
        assertFalse(BriefActivityPolicy.shouldStart(snapshot("2026-07-29"), today))
    }

    @Test
    fun promptsAfterMoreThanThreeMissedCalendarDays() {
        assertTrue(BriefActivityPolicy.shouldStart(snapshot("2026-07-28"), today))
    }

    @Test
    fun completeEmptyWindowCanPromptWithoutKnownLastTweet() {
        val start = today.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertTrue(
            BriefActivityPolicy.shouldStart(
                StreakSnapshot(
                    streak = 0,
                    activeToday = false,
                    lastActiveDay = null,
                    activityWindowStartAt = start,
                    activityCheckedAt = start + 7 * 24 * 60 * 60 * 1000L,
                    activityComplete = true,
                ),
                today,
            ),
        )
    }

    @Test
    fun incompleteActivityNeverClaimsInactivity() {
        val start = today.minusDays(7).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertFalse(
            BriefActivityPolicy.shouldStart(
                StreakSnapshot(
                    streak = 0,
                    activeToday = false,
                    lastActiveDay = null,
                    activityWindowStartAt = start,
                    activityCheckedAt = start + 7 * 24 * 60 * 60 * 1000L,
                    activityComplete = false,
                ),
                today,
            ),
        )
    }

    private fun snapshot(lastDay: String) = StreakSnapshot(
        streak = 0,
        activeToday = false,
        lastActiveDay = lastDay,
    )
}
