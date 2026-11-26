package com.tjg.twidget.brief

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefBlueThemeTest {
    @Test fun bothScreensShareOneContinuousMotionClock() {
        assertEquals(.5f, BriefBlueBackground.motionPhase(9_000), .0001f)
        assertEquals(0f, BriefBlueBackground.motionPhase(18_000), .0001f)
        assertEquals(.5f, BriefBlueBackground.motionPhase(27_000), .0001f)
    }
    @Test fun localTimeAndWeekendsChooseTheBlueDesign() {
        fun theme(time: String) = BriefBlueTheme.current(ZonedDateTime.parse(time))
        assertEquals(BriefBlueTheme.MORNING, theme("2026-11-26T09:00:00Z"))
        assertEquals(BriefBlueTheme.AFTERNOON, theme("2026-11-26T12:00:00Z"))
        assertEquals(BriefBlueTheme.EVENING, theme("2026-11-26T17:00:00Z"))
        assertEquals(BriefBlueTheme.NIGHT, theme("2026-11-26T23:00:00Z"))
        assertEquals(BriefBlueTheme.NIGHT, theme("2026-11-28T04:59:00Z"))
        assertEquals(BriefBlueTheme.WEEKEND, theme("2026-11-28T09:00:00Z"))
        assertEquals(BriefBlueTheme.AFTERNOON, theme("2026-11-28T13:00:00Z"))
        assertEquals(BriefBlueTheme.MORNING, theme("2026-11-26T09:00:00+09:00"))
    }
}
