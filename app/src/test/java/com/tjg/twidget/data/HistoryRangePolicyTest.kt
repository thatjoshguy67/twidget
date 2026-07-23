package com.tjg.twidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRangePolicyTest {
    @Test
    fun allTimeIsAlwaysAvailable() {
        assertTrue(HistoryRangePolicy.isAvailable(HistoryRange.ALL_TIME, emptyList()))
    }

    @Test
    fun weekRequiresSevenDaysOfSpan() {
        val shortSpan = listOf(sample(dayOffset = 0), sample(dayOffset = 2))
        val fullSpan = listOf(sample(dayOffset = 0), sample(dayOffset = 6))
        assertFalse(HistoryRangePolicy.isAvailable(HistoryRange.WEEK, shortSpan))
        assertTrue(HistoryRangePolicy.isAvailable(HistoryRange.WEEK, fullSpan))
    }

    @Test
    fun resolveSavedRangeFallsBackWhenSavedRangeUnavailable() {
        val samples = listOf(sample(dayOffset = 0), sample(dayOffset = 2))
        assertEquals(HistoryRange.ALL_TIME, HistoryRangePolicy.resolveSavedRange(HistoryRange.MONTH, samples))
    }

    private fun sample(dayOffset: Int): HistorySample {
        val dayMillis = 24 * 60 * 60 * 1000L
        return HistorySample(
            dayLabel = "Day $dayOffset",
            followers = 100L + dayOffset,
            following = 10L,
            posts = 1L,
            likes = 5L,
            timestamp = dayOffset * dayMillis,
            followersKnown = true,
            followingKnown = true,
            postsKnown = true,
            likesKnown = true,
        )
    }
}
