package com.tjg.twidget.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DailyStreakStoreTest {
    @Test
    fun countsConsecutiveDaysThroughToday() {
        val today = LocalDate.of(2026, 7, 22)
        val days = setOf("2026-07-20", "2026-07-21", "2026-07-22")
        assertEquals(3, DailyStreakStore.computeStreak(days, today))
    }

    @Test
    fun countsStreakThroughYesterdayWhenInactiveToday() {
        val today = LocalDate.of(2026, 7, 22)
        val days = setOf("2026-07-20", "2026-07-21")
        assertEquals(2, DailyStreakStore.computeStreak(days, today))
    }

    @Test
    fun returnsZeroWhenGapBreaksStreak() {
        val today = LocalDate.of(2026, 7, 22)
        val days = setOf("2026-07-20", "2026-07-22")
        assertEquals(1, DailyStreakStore.computeStreak(days, today))
    }
}
