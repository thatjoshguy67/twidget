package com.tjg.twidget.followers

import org.junit.Assert.*
import org.junit.Test

class FollowerFiltersTest {
    private val ranked = TopFollowersBrowserPolicy.rank(listOf(
        TopFollower("1", "zebra", "Zebra", 100_000, false, ""),
        TopFollower("2", "amy", "Amy", 10_000, false, ""),
        TopFollower("3", "alan", "Alan", 9_999, false, ""),
    ))
    @Test fun thresholdIncludesBoundaryAndKeepsOriginalRanks() {
        val result = FollowerFilters(FollowerSort.NAME, 10_000).apply(ranked, "")
        assertEquals(listOf("amy", "zebra"), result.map { it.follower.username })
        assertEquals(listOf(2, 1), result.map { it.rank })
    }
    @Test fun queryAndThresholdCompose() {
        assertEquals(listOf(2), FollowerFilters(minimum = 10_000).apply(ranked, "@AMY").map { it.rank })
        assertTrue(FollowerFilters(minimum = 100_000).apply(ranked, "amy").isEmpty())
    }
    @Test fun resetReturnsCanonicalOrder() {
        assertEquals(ranked, FollowerFilters().apply(ranked, ""))
        assertEquals(2, FollowerFilters(FollowerSort.NAME, 10_000).activeCount)
    }
    @Test fun invalidRestoredSettingsFallBackToDefaults() {
        assertEquals(FollowerFilters(), FollowerFilters.restored("missing", -1))
        assertEquals(FollowerFilters(FollowerSort.NAME, 100_000), FollowerFilters.restored("NAME", 100_000))
    }
}
