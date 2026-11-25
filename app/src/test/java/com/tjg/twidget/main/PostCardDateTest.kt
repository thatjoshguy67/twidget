package com.tjg.twidget.main

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PostCardDateTest {
    private fun label(date: String) = PostCardDate.format(LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        LocalDate.of(2026, 11, 25), Locale.US, ZoneOffset.UTC)
    @Test fun currentYearOmitsYear() { assertEquals("Sep 26th", label("2026-09-26")) }
    @Test fun previousCalendarYearIncludesYearEvenWithinTwelveMonths() { assertEquals("Dec 26th, 2025", label("2025-12-26")) }
    @Test fun ordinalExceptions() { assertEquals("Nov 11th", label("2026-11-11")); assertEquals("Nov 21st", label("2026-11-21")) }
}
