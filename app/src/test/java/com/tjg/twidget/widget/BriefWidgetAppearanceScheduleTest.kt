package com.tjg.twidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class BriefWidgetAppearanceScheduleTest {
    @Test fun refreshesAtEveryLocalDayPartWithoutDependingOnNetworkSync() {
        for ((input, expected) in listOf(
            "2026-11-26T04:59:00Z" to "2026-11-26T05:00:00Z",
            "2026-11-26T05:00:00Z" to "2026-11-26T12:00:00Z",
            "2026-11-26T12:00:00Z" to "2026-11-26T17:00:00Z",
            "2026-11-26T17:00:00Z" to "2026-11-26T23:00:00Z",
            "2026-11-26T23:00:00Z" to "2026-11-27T05:00:00Z",
            "2026-11-28T00:00:00Z" to "2026-11-28T05:00:00Z",
        )) assertEquals(ZonedDateTime.parse(expected), BriefWidgetAppearanceSchedule.nextChange(ZonedDateTime.parse(input)))
    }

    @Test fun followsLocalZoneAcrossTheDstChange() {
        val now = ZonedDateTime.parse("2027-03-27T23:30:00Z[Europe/London]")
        assertEquals(ZonedDateTime.parse("2027-03-28T05:00:00+01:00[Europe/London]"), BriefWidgetAppearanceSchedule.nextChange(now))
        assertEquals(ZonedDateTime.parse("2026-11-26T17:00:00+09:00[Asia/Tokyo]"),
            BriefWidgetAppearanceSchedule.nextChange(ZonedDateTime.parse("2026-11-26T13:00:00+09:00[Asia/Tokyo]")))
    }
}
