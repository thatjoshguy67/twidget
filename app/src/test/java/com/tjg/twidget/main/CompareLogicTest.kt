package com.tjg.twidget.main

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompareLogicTest {
    @Test fun winnerHandlesTiesAndMissingValues() {
        assertEquals(CompareEmphasis.BOTH, CompareLogic.emphasis(5.0, 5.0))
        assertEquals(CompareEmphasis.LEFT, CompareLogic.emphasis(5.0, 2.0))
        assertEquals(CompareEmphasis.RIGHT, CompareLogic.emphasis(null, 2.0))
        assertEquals(CompareEmphasis.NEITHER, CompareLogic.emphasis(null, null))
    }
    @Test fun weeklyDeltaRequiresARealWeekOldBaseline() {
        val day = 86_400_000L
        val now = 100 * day
        assertEquals(3L, CompareLogic.weeklyDelta(103, listOf(CompareHistoryPoint(now - 7 * day, 100)), now))
        assertNull(CompareLogic.weeklyDelta(103, listOf(CompareHistoryPoint(now - 2 * day, 100)), now))
        assertEquals("+3", CompareLogic.signed(3, Locale.US))
        assertEquals("-7", CompareLogic.signed(-7, Locale.US))
        assertEquals(DeltaTone.POSITIVE, CompareLogic.tone(3))
        assertEquals(DeltaTone.NEGATIVE, CompareLogic.tone(-7))
        assertEquals(DeltaTone.NEUTRAL, CompareLogic.tone(0))
    }
    @Test fun engagementUsesKnownPostCountersAndViews() {
        val post = ComparePostCounts(10, 4, 3, 2, 100)
        val totals = CompareLogic.totals(listOf(post))
        assertEquals(0.19, totals.engagementRate!!, 0.00001)
        assertNull(CompareLogic.totals(listOf(post.copy(quotes = null))).engagementRate)
        assertNull(CompareLogic.totals(listOf(post.copy(views = 0))).engagementRate)
        assertNull(CompareLogic.totals(emptyList()).likes)
    }
    @Test fun compactFormattingUsesLocaleAndPromotesRoundedThousands() {
        assertEquals("28K", CompareLogic.compact(28_000, Locale.US))
        assertEquals("5.7K", CompareLogic.compact(5_700, Locale.US))
        assertEquals("5,7K", CompareLogic.compact(5_700, Locale.GERMANY))
        assertEquals("1M", CompareLogic.compact(999_950, Locale.US))
    }
}
