package com.tjg.twidget.analytics

import java.time.LocalDate
import kotlin.math.ceil

enum class AnalyticsRange(val days: Long) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    YEAR(366),
}

enum class ImportedAnalyticsMetric {
    IMPRESSIONS,
    ENGAGEMENTS,
    LIKES,
    PROFILE_VISITS,
    REPLIES,
    REPOSTS,
    SHARES,
    BOOKMARKS,
    POSTS_CREATED,
    VIDEO_VIEWS,
    MEDIA_VIEWS,
    NEW_FOLLOWS,
    UNFOLLOWS;

    fun value(sample: XAnalyticsMovement): Long? = when (this) {
        IMPRESSIONS -> sample.impressions
        ENGAGEMENTS -> sample.engagements
        LIKES -> sample.likes
        PROFILE_VISITS -> sample.profileVisits
        REPLIES -> sample.replies
        REPOSTS -> sample.reposts
        SHARES -> sample.shares
        BOOKMARKS -> sample.bookmarks
        POSTS_CREATED -> sample.postsCreated
        VIDEO_VIEWS -> sample.videoViews
        MEDIA_VIEWS -> sample.mediaViews
        NEW_FOLLOWS -> sample.newFollows
        UNFOLLOWS -> sample.unfollows
    }
}

data class ImportedMetricSummary(
    val metric: ImportedAnalyticsMetric,
    val total: Long,
    val dataPoints: Int,
) {
    val dailyAverage: Double
        get() = if (dataPoints == 0) 0.0 else total.toDouble() / dataPoints
}

data class ImportedAnalyticsSummary(
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val importedDays: Int,
    val newFollows: Long,
    val unfollows: Long,
    val metrics: List<ImportedMetricSummary>,
) {
    val netFollows: Long
        get() = newFollows - unfollows

    val engagementRate: Double?
        get() {
            val impressions = metric(ImportedAnalyticsMetric.IMPRESSIONS)?.total ?: return null
            val engagements = metric(ImportedAnalyticsMetric.ENGAGEMENTS)?.total ?: return null
            return if (impressions > 0) engagements.toDouble() / impressions else null
        }

    fun metric(metric: ImportedAnalyticsMetric): ImportedMetricSummary? =
        metrics.firstOrNull { it.metric == metric }
}

data class ImportedChartPoint(
    val label: String,
    val value: Long,
)

object AnalyticsInsights {
    fun select(
        samples: List<XAnalyticsMovement>,
        range: AnalyticsRange,
    ): List<XAnalyticsMovement> {
        val end = samples.maxOfOrNull(XAnalyticsMovement::date) ?: return emptyList()
        val start = end.minusDays(range.days - 1)
        return samples.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            .sortedBy(XAnalyticsMovement::date)
    }

    fun summarize(samples: List<XAnalyticsMovement>): ImportedAnalyticsSummary? {
        if (samples.isEmpty()) return null
        val sorted = samples.sortedBy(XAnalyticsMovement::date)
        val metrics = ImportedAnalyticsMetric.entries.mapNotNull { metric ->
            val values = sorted.mapNotNull(metric::value)
            values.takeIf { it.isNotEmpty() }?.let {
                ImportedMetricSummary(metric, it.sum(), it.size)
            }
        }
        return ImportedAnalyticsSummary(
            firstDate = sorted.first().date,
            lastDate = sorted.last().date,
            importedDays = sorted.size,
            newFollows = sorted.sumOf(XAnalyticsMovement::newFollows),
            unfollows = sorted.sumOf(XAnalyticsMovement::unfollows),
            metrics = metrics,
        )
    }

    fun chartPoints(
        samples: List<XAnalyticsMovement>,
        metric: ImportedAnalyticsMetric,
        maxPoints: Int = 18,
    ): List<ImportedChartPoint> {
        require(maxPoints > 0) { "maxPoints must be positive" }
        val values = samples.sortedBy(XAnalyticsMovement::date)
            .mapNotNull { sample -> metric.value(sample)?.let { sample.date to it } }
        if (values.isEmpty()) return emptyList()
        val bucketSize = ceil(values.size.toDouble() / maxPoints).toInt().coerceAtLeast(1)
        return values.chunked(bucketSize).map { bucket ->
            ImportedChartPoint(
                label = bucket.last().first.toString().substring(5),
                value = bucket.sumOf { it.second },
            )
        }
    }
}
