package com.tjg.twidget.followers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TopFollowersScanPolicyTest {
    private val london = ZoneId.of("Europe/London")

    @Test
    fun blocksAnotherScanOnTheSameLocalCalendarDay() {
        val morning = millis(2026, 7, 18, 0, 1)
        val evening = millis(2026, 7, 18, 23, 59)
        val day = TopFollowersScanPolicy.localDay(morning, london)

        assertFalse(TopFollowersScanPolicy.canStart(day, evening, london))
    }

    @Test
    fun allowsScanAsSoonAsTheLocalCalendarDayChanges() {
        val previous = millis(2026, 7, 18, 23, 59)
        val next = millis(2026, 7, 19, 0, 0)
        val day = TopFollowersScanPolicy.localDay(previous, london)

        assertTrue(TopFollowersScanPolicy.canStart(day, next, london))
    }

    @Test
    fun allowsAnIncompleteScanToResumeOnTheSameDay() {
        val morning = millis(2026, 7, 18, 8, 0)
        val evening = millis(2026, 7, 18, 20, 0)
        val day = TopFollowersScanPolicy.localDay(morning, london)

        assertTrue(TopFollowersScanPolicy.canStart(day, false, evening, london))
        assertFalse(TopFollowersScanPolicy.canStart(day, true, evening, london))
    }

    @Test
    fun localDayUsesTheAccountDeviceTimezoneNotUtc() {
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val localLateEvening = LocalDateTime.of(2026, 7, 18, 23, 30)
            .atZone(losAngeles)
            .toInstant()
            .toEpochMilli()

        assertFalse(TopFollowersScanPolicy.canStart("2026-07-18", localLateEvening, losAngeles))
        assertTrue(TopFollowersScanPolicy.canStart("2026-07-18", localLateEvening, ZoneId.of("UTC")))
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(london)
            .toInstant()
            .toEpochMilli()
}
