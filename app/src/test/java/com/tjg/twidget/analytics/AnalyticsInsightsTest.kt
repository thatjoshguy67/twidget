package com.tjg.twidget.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AnalyticsInsightsTest {
    @Test
    fun rangeEndsAtLatestImportedDayAndKeepsHonestGaps() {
        val samples = listOf(
            sample("2026-01-01", impressions = 10),
            sample("2026-01-20", impressions = 20),
            sample("2026-01-31", impressions = 30),
        )

        val selected = AnalyticsInsights.select(samples, AnalyticsRange.MONTH)

        assertEquals(listOf("2026-01-20", "2026-01-31"), selected.map { it.date.toString() })
    }

    @Test
    fun summaryUsesOnlyAvailableMetricDays() {
        val samples = listOf(
            sample("2026-01-01", impressions = 100, engagements = 10, newFollows = 4, unfollows = 1),
            sample("2026-01-02", impressions = null, engagements = 20, newFollows = 2, unfollows = 3),
        )

        val summary = requireNotNull(AnalyticsInsights.summarize(samples))
        assertNotNull(summary)

        assertEquals(6L, summary.newFollows)
        assertEquals(4L, summary.unfollows)
        assertEquals(2L, summary.netFollows)
        assertEquals(1, summary.metric(ImportedAnalyticsMetric.IMPRESSIONS)?.dataPoints)
        assertEquals(30L, summary.metric(ImportedAnalyticsMetric.ENGAGEMENTS)?.total)
        assertEquals(0.3, summary.engagementRate!!, 0.0001)
    }

    @Test
    fun chartSeriesBucketsLongRangesWithoutInventingValues() {
        val samples = (1..10).map { day ->
            sample("2026-01-${day.toString().padStart(2, '0')}", impressions = day.toLong())
        }

        val points = AnalyticsInsights.chartPoints(
            samples,
            ImportedAnalyticsMetric.IMPRESSIONS,
            maxPoints = 3,
        )

        assertEquals(listOf(10L, 26L, 19L), points.map(ImportedChartPoint::value))
        assertEquals(listOf("01-04", "01-08", "01-10"), points.map(ImportedChartPoint::label))
    }

    private fun sample(
        date: String,
        impressions: Long? = null,
        engagements: Long? = null,
        newFollows: Long = 0,
        unfollows: Long = 0,
    ) = XAnalyticsMovement(
        date = LocalDate.parse(date),
        newFollows = newFollows,
        unfollows = unfollows,
        impressions = impressions,
        engagements = engagements,
    )
}
