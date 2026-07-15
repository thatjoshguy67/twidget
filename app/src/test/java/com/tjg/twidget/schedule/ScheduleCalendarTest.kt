package com.tjg.twidget.schedule

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleCalendarTest {
    private val zone = ZoneId.of("Europe/London")

    @Test
    fun monthAndDayFilteringUseTheVisibleTimeZone() {
        val june = post("june", LocalDate.of(2026, 6, 30), 23)
        val julyMorning = post("morning", LocalDate.of(2026, 7, 1), 9)
        val julyEvening = post("evening", LocalDate.of(2026, 7, 1), 18)

        assertEquals(
            listOf("morning", "evening"),
            ScheduleCalendar.postsInMonth(listOf(june, julyEvening, julyMorning), YearMonth.of(2026, 7), zone)
                .map(ScheduledPost::id),
        )
        assertEquals(
            listOf("morning", "evening"),
            ScheduleCalendar.postsOnDate(
                listOf(june, julyEvening, julyMorning),
                LocalDate.of(2026, 7, 1),
                zone,
            ).map(ScheduledPost::id),
        )
    }

    @Test
    fun undatedDraftsAreNotInventedIntoCalendar() {
        val draft = ScheduledPost(
            id = "draft",
            provider = ScheduleProvider.LOCAL_REMINDER,
            accountUsername = "tester",
            scheduledAt = null,
            thread = listOf(ScheduleThreadItem(text = "Draft")),
        )

        assertEquals(emptyList<ScheduledPost>(), ScheduleCalendar.postsInMonth(
            listOf(draft),
            YearMonth.of(2026, 7),
            zone,
        ))
    }

    private fun post(id: String, date: LocalDate, hour: Int): ScheduledPost = ScheduledPost(
        id = id,
        provider = ScheduleProvider.LOCAL_REMINDER,
        accountUsername = "tester",
        scheduledAt = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        thread = listOf(ScheduleThreadItem(text = id)),
    )
}
