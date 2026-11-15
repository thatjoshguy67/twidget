package com.tjg.twidget.main

import com.tjg.twidget.data.HistorySample
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DashboardDailyPostsTest {
    private val today = LocalDate.of(2026, 11, 15)
    private val zone = ZoneId.of("Europe/London")
    private fun sample(day: LocalDate, count: Long, estimated: Boolean = false) = HistorySample(
        day.toString(), 0, 0, count, 0, day.atStartOfDay(zone).toInstant().toEpochMilli(), estimated = estimated)
    private fun week(end: LocalDate = today) = (7L downTo 0L).mapIndexed { index, ago -> sample(end.minusDays(ago), 100L + index * 10) }

    @Test fun sevenDayWindowUsesTheEighthBaselineAndHasSevenBars() {
        val points = DashboardDailyPosts.points(week(), today = today, zone = zone)
        assertEquals(7, points.size)
        assertEquals(List(7) { 10L }, points.map { it.net })
        assertEquals(70L, points.mapNotNull { it.net }.sum())
        assertEquals(today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli(), points.first().timestamp)
    }
    @Test fun absentFirstBaselineStaysUnknownRatherThanBecomingZero() {
        val points = DashboardDailyPosts.points(week().drop(1), today = today, zone = zone)
        assertEquals(7, points.size)
        assertNull(points.first().net)
        assertEquals(List(6) { 10L }, points.drop(1).map { it.net })
    }
    @Test fun gapDoesNotSmearSeveralDaysIntoOneBar() {
        val points = DashboardDailyPosts.points(week().filterNot { it.timestamp == sample(today.minusDays(3), 0).timestamp }, today = today, zone = zone)
        assertEquals(7, points.size)
        assertNull(points[3].net)
        assertNull(points[4].net)
        assertEquals(5, points.count { it.net != null })
    }
    @Test fun estimatedBaselineIsNotTreatedAsAnObservedDailyClose() {
        val samples = week().toMutableList().apply { this[0] = this[0].copy(estimated = true) }
        assertNull(DashboardDailyPosts.points(samples, today = today, zone = zone).first().net)
    }
    @Test fun localCalendarDaysSurviveDaylightSavingAndRetainDecreases() {
        val end = LocalDate.of(2026, 10, 26)
        val samples = week(end).toMutableList().apply { this[lastIndex] = last().copy(posts = 155) }
        val points = DashboardDailyPosts.points(samples, today = end, zone = zone)
        assertEquals(7, points.size)
        assertEquals(-5L, points.last().net)
        assertEquals(25L * 3600_000, points.last().timestamp - points[5].timestamp)
    }
}
