package com.tjg.twidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRangePolicyTest {
    @Test
    fun allTimeChartIncludesLegacyEstimatesBeforeTodaysVerifiedSample() {
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val old = sample(0).copy(timestamp = today - 30L * 86400000, estimated = true)
        val current = sample(0).copy(timestamp = today, followers = 200)
        val chart = TwidgetStore.chartHistory(listOf(old, current), HistoryRange.ALL_TIME)
        assertTrue(chart.size > 1)
        assertEquals(old.timestamp, chart.first().timestamp)
        assertTrue(chart.first().estimated)
        assertEquals(current.followers, chart.last().followers)
        assertFalse(chart.last().estimated)
    }

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

    @Test
    fun calendarCoverageDoesNotLoseADayAcrossSpringClockChange() {
        val oldZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
            val zone = java.time.ZoneId.systemDefault()
            val first = java.time.LocalDate.of(2026, 3, 25)
            val last = first.plusDays(6)
            val history = listOf(
                sample(0).copy(timestamp = first.atStartOfDay(zone).toInstant().toEpochMilli()),
                sample(0).copy(timestamp = last.atStartOfDay(zone).toInstant().toEpochMilli()),
            )
            assertEquals(7, HistoryRangePolicy.historySpanDays(history))
            assertTrue(HistoryRangePolicy.isAvailable(HistoryRange.WEEK, history))
        } finally {
            java.util.TimeZone.setDefault(oldZone)
        }
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
