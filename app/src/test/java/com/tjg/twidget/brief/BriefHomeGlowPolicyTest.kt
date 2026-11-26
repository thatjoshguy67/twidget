package com.tjg.twidget.brief

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class BriefHomeGlowPolicyTest {
    @Test fun restartAndDecliningCardsDoNotUsePositiveGrowthColours() {
        assertEquals(BriefHomeGlowMood.RESET, BriefHomeGlowPolicy.mood(BriefCardType.STREAK, 3, -21, 0))
        assertEquals(BriefHomeGlowMood.GROWTH, BriefHomeGlowPolicy.mood(BriefCardType.STREAK, 3, -21, 6))
        assertEquals(BriefHomeGlowMood.RESET, BriefHomeGlowPolicy.mood(BriefCardType.FOLLOWER_CONTEXT, 3, -21, 0))
        assertEquals(BriefHomeGlowMood.GROWTH, BriefHomeGlowPolicy.mood(BriefCardType.FOLLOWER_CONTEXT, 3, 21, 0))
    }

    @Test fun contentAndDayPartBothChangeTheGlowWhileKeepingItsPlacementSubtle() {
        val morning = ZonedDateTime.parse("2026-11-26T09:00:00Z")
        val evening = ZonedDateTime.parse("2026-11-26T19:00:00Z")
        for (dark in listOf(false, true)) {
            val first = BriefHomeGlowPolicy.spec(BriefHomeGlowMood.RESET, dark, morning)
            val later = BriefHomeGlowPolicy.spec(BriefHomeGlowMood.RESET, dark, evening)
            assertNotEquals(first.color, later.color)
            assertNotEquals(first.offset, later.offset)
            assertNotEquals(first.color, BriefHomeGlowPolicy.spec(BriefHomeGlowMood.GROWTH, dark, morning).color)
            for (mood in BriefHomeGlowMood.entries) assertTrue(kotlin.math.abs(BriefHomeGlowPolicy.spec(mood, dark, morning).offset) < .12f)
        }
    }
}
