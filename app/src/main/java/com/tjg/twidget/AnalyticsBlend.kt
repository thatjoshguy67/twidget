package com.tjg.twidget

import java.time.LocalDate

data class BlendedAnalytics(
    val avgViews: Double?,
    val avgEngagements: Double?,
    val engagementRate: Double?,
    val livePosts: Int,
    val importedPosts: Long,
    val importedDays: Int,
    val usesImportedViews: Boolean,
    val usesImportedEngagements: Boolean,
    val usesImportedRate: Boolean,
)

/**
 * Uses recent CSV snapshots as additional observations for live post averages.
 * Raw server totals/medians and raw imported totals remain separate in the UI.
 */
object AnalyticsBlendPolicy {
    fun blend(
        server: PostAnalytics?,
        imported: List<XAnalyticsMovement>,
        today: LocalDate = LocalDate.now(),
        windowDays: Int = server?.windowDays?.coerceAtLeast(1) ?: 7,
    ): BlendedAnalytics {
        val firstDate = today.minusDays((windowDays - 1).toLong())
        val recent = imported.filter { !it.date.isBefore(firstDate) && !it.date.isAfter(today) }
        val importedPosts = recent.mapNotNull { it.postsCreated }.sum()
        val importedViews = recent.mapNotNull { it.impressions }.takeIf { it.isNotEmpty() }?.sum()
        val importedEngagements = recent.mapNotNull { it.engagements }.takeIf { it.isNotEmpty() }?.sum()
        val livePosts = server?.postsAnalyzed?.coerceAtLeast(0) ?: 0

        val useImportedViews = importedPosts > 0 && importedViews != null
        val useImportedEngagements = importedPosts > 0 && importedEngagements != null
        val useImportedRate = importedViews != null && importedViews > 0 && importedEngagements != null
        val viewTotal = (server?.totalViews?.takeIf { livePosts > 0 } ?: 0L) +
            (importedViews?.takeIf { useImportedViews } ?: 0L)
        val viewPosts = livePosts.toLong() + importedPosts.takeIf { useImportedViews }.orZero()
        val engagementTotal = (server?.totalEngagements?.takeIf { livePosts > 0 } ?: 0L) +
            (importedEngagements?.takeIf { useImportedEngagements } ?: 0L)
        val engagementPosts = livePosts.toLong() + importedPosts.takeIf { useImportedEngagements }.orZero()

        val liveRateViews = server?.totalViews?.takeIf { it > 0 } ?: 0L
        val liveRateEngagements = server?.totalEngagements?.takeIf { liveRateViews > 0 } ?: 0L
        val rateViews = liveRateViews + (importedViews?.takeIf { useImportedRate } ?: 0L)
        val rateEngagements = liveRateEngagements +
            (importedEngagements?.takeIf { useImportedRate } ?: 0L)

        return BlendedAnalytics(
            avgViews = viewTotal.toDouble().takeIf { viewPosts > 0 }?.div(viewPosts),
            avgEngagements = engagementTotal.toDouble().takeIf { engagementPosts > 0 }?.div(engagementPosts),
            engagementRate = rateEngagements.toDouble().takeIf { rateViews > 0 }?.div(rateViews),
            livePosts = livePosts,
            importedPosts = importedPosts,
            importedDays = recent.size,
            usesImportedViews = useImportedViews,
            usesImportedEngagements = useImportedEngagements,
            usesImportedRate = useImportedRate,
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
