package com.tjg.twidget.analytics

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InteractionAggregationTest {
    private val today = LocalDate.of(2026, 6, 10)
    private fun post(id: String, date: LocalDate, likes: Long?, reply: Boolean = false) = InteractionPost(
        id, date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), 100, reply,
        InteractionCounts(likes = likes, reposts = 1, replies = 1, quotes = 1),
    )
    @Test fun csvWinsAndMissingDaysRemainGaps() {
        val data = InteractionData(listOf(InteractionDay(today, InteractionCounts(likes = 0))),
            listOf(post("1", today, 50), post("2", today.minusDays(1), 8)))
        val points = InteractionAggregation.series(data, InteractionMetric.LIKES, 3, today)
        assertEquals(listOf(null, 8L, 0L), points.map { it.value })
        assertEquals(8L, InteractionAggregation.total(points))
        assertEquals(8L, InteractionAggregation.totals(data, 3, today)[InteractionMetric.LIKES])
        assertNull(InteractionAggregation.series(data, InteractionMetric.SHARES, 1, today).single().value)
    }
    @Test fun missingPostCounterDoesNotBecomeZero() {
        val data = InteractionData(posts = listOf(post("1", today, 10), post("2", today, null)))
        assertNull(InteractionAggregation.series(data, InteractionMetric.LIKES, 1, today).single().value)
    }
    @Test fun mergeKeepsLargestCountersAndNewestMetadata() {
        val original = post("1", today, 10)
        val later = original.copy(capturedAt = 200, counts = InteractionCounts(likes = 3, views = 0))
        val merged = mergeInteractions(InteractionData(posts = listOf(original)), InteractionData(posts = listOf(later))).posts.single()
        assertEquals(10L, merged.counts.likes)
        assertEquals(0L, merged.counts.views)
        assertEquals(200L, merged.capturedAt)
    }
    @Test fun mixAndSplitRoundToOneHundred() {
        val data = InteractionData(posts = listOf(post("1", today, 1), post("2", today, 1, true), post("3", today, 1, true)))
        val mix = InteractionAggregation.mix(data, 7, today)
        assertEquals(100, mix.sumOf { it.percent ?: 0 })
        val split = InteractionAggregation.split(data, 7, today)
        assertEquals(1, split.originals)
        assertEquals(2, split.replies)
        assertEquals(100, split.originalPercent + split.replyPercent)
        assertEquals(33, split.originalPercent)
    }
    @Test fun mixRequiresMatchingDayCoverage() {
        val data = InteractionData(daily = listOf(
            InteractionDay(today, InteractionCounts(likes = 1, reposts = 1, replies = 1, quotes = 1)),
            InteractionDay(today.minusDays(1), InteractionCounts(likes = 5)),
        ))
        assertNull(InteractionAggregation.mix(data, 7, today).first().percent)
    }
    @Test fun unknownMixDoesNotPretendMissingQuotesAreZero() {
        val data = InteractionData(daily = listOf(InteractionDay(today, InteractionCounts(likes = 2))))
        assertNull(InteractionAggregation.mix(data, 1, today).first().percent)
    }
}
