package com.tjg.twidget.main

import com.tjg.twidget.data.StreakSnapshot
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class StreakCardPolicyTest {
    @Test
    fun streakUsesTheStandardOneColumnCardFootprint() {
        assertEquals(DashboardCardSize.HALF, DashboardCardType.DAILY_STREAK.size)
        assertEquals(1, DashboardCardType.DAILY_STREAK.size.span)
        assertEquals(140, DashboardCardType.DAILY_STREAK.size.heightDp)
    }

    @Test
    fun activeTodayIsSafe() {
        assertEquals(
            StreakCardState.SAFE,
            StreakCardPolicy.state(
                StreakSnapshot(streak = 67, activeToday = true, lastActiveDay = "2026-07-29"),
                LocalTime.of(23, 59),
            ),
        )
    }

    @Test
    fun inactiveStreakUsesOrdinaryReminderBeforeFinalTenMinutes() {
        assertEquals(
            StreakCardState.NEEDS_ACTIVITY,
            StreakCardPolicy.state(
                StreakSnapshot(streak = 67, activeToday = false, lastActiveDay = "2026-07-28"),
                LocalTime.of(18, 30),
            ),
        )
    }

    @Test
    fun inactiveStreakWarnsDuringFinalTenMinutes() {
        assertEquals(
            StreakCardState.EXPIRING,
            StreakCardPolicy.state(
                StreakSnapshot(streak = 67, activeToday = false, lastActiveDay = "2026-07-28"),
                LocalTime.of(23, 50),
            ),
        )
    }

    @Test
    fun zeroStreakOffersRevival() {
        assertEquals(
            StreakCardState.REVIVE,
            StreakCardPolicy.state(
                StreakSnapshot(streak = 0, activeToday = false, lastActiveDay = null),
                LocalTime.NOON,
            ),
        )
    }
}
