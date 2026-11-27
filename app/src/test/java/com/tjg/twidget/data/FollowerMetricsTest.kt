package com.tjg.twidget.data

import com.tjg.twidget.brief.BriefContextPolicy
import com.tjg.twidget.main.CompareHistoryPoint
import com.tjg.twidget.main.CompareLogic
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class FollowerMetricsTest {
    private val zone = ZoneId.systemDefault()
    private val date = LocalDate.of(2026, 10, 28)
    private fun time(daysAgo: Long) = date.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli()
    private val stats get() = ProfileStats("Example", "example", 120, 10, 20, 0, syncedAt = time(0) + 3600000)
    private fun sample(daysAgo: Long, count: Long = 100) = HistorySample("day", count, 10, 20, 0, time(daysAgo))

    @Test fun briefAndCompareUseTheSameCalendarWeekAcrossClockChanges() {
        val history = listOf(sample(8, 40), sample(7), sample(6, 110))
        assertEquals(20L, FollowerMetrics.weeklyDelta(stats, history))
        assertEquals(BriefContextPolicy.weeklyFollowerDelta(stats, history),
            CompareLogic.weeklyDelta(stats.followersCount, history.map { CompareHistoryPoint(it.timestamp, it.followers) }, stats.syncedAt))
    }

    @Test fun nearbyDaysCannotBeSubstitutedForAWeekOldMeasurement() {
        val history = listOf(sample(8), sample(6))
        assertNull(FollowerMetrics.weeklyDelta(stats, history))
        assertNull(CompareLogic.weeklyDelta(stats.followersCount, history.map { CompareHistoryPoint(it.timestamp, it.followers) }, stats.syncedAt))
    }

    @Test fun estimatedUnknownNegativeAndFutureSamplesCannotSupportGrowth() {
        val history = listOf(sample(7).copy(estimated = true), sample(7).copy(followersKnown = false), sample(7, -1), sample(-1))
        assertNull(FollowerMetrics.weeklyDelta(stats, history))
        assertNull(FollowerMetrics.weeklyDelta(stats.copy(followersKnown = false), listOf(sample(7))))
        assertEquals(-100L, FollowerMetrics.weeklyDelta(stats.copy(followersCount = 0), listOf(sample(7))))
    }

    @Test fun dailyDeltaRespectsAvailabilityForTheSameSnapshotUsedToRenderTheCount() {
        fun delta(profile: ProfileStats, baseline: HistorySample?) =
            TwidgetStore.AccountSnapshot(profile, emptyList(), baseline).todayDelta({ it.followersKnown }) { it.followers }
        assertEquals(20L, delta(stats, sample(1)))
        assertEquals(0L, delta(stats.copy(followersKnown = false), sample(1)))
        assertEquals(0L, delta(stats, sample(1).copy(followersKnown = false)))
        assertEquals(0L, delta(stats, null))
        assertEquals(-100L, delta(stats.copy(followersCount = 0), sample(1)))
    }
}
