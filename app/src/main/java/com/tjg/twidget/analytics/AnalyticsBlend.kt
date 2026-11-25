package com.tjg.twidget.analytics

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
        val sample = HistoricalPostArchive.forStats(server)
        val originalUrls = sample?.recentPosts.orEmpty().mapTo(mutableSetOf()) { it.url }
        val additionalReplies = sample?.recentReplies.orEmpty().distinctBy { it.url }
            .filter { it.url !in originalUrls }
        val originalPosts = sample?.postsAnalyzed?.coerceAtLeast(0) ?: 0
        val livePosts = originalPosts + additionalReplies.size
        val originals = sample?.recentPosts.orEmpty()
        val liveViewPosts = (if (originals.isEmpty()) originalPosts else originals.count { it.viewsKnown }) + additionalReplies.count { it.viewsKnown }
        val liveViews = (if (originals.isEmpty()) sample?.totalViews ?: 0L else originals.filter { it.viewsKnown }.sumOf { it.views }) +
            additionalReplies.filter { it.viewsKnown }.sumOf { it.views }
        val liveEngagements = (sample?.totalEngagements ?: 0L) + additionalReplies.sumOf { it.engagements }

        val useImportedViews = importedPosts > 0 && importedViews != null
        val useImportedEngagements = importedPosts > 0 && importedEngagements != null
        val useImportedRate = importedViews != null && importedViews > 0 && importedEngagements != null
        val viewTotal = liveViews.takeIf { liveViewPosts > 0 }.orZero() +
            (importedViews?.takeIf { useImportedViews } ?: 0L)
        val viewPosts = liveViewPosts.toLong() + importedPosts.takeIf { useImportedViews }.orZero()
        val engagementTotal = liveEngagements.takeIf { livePosts > 0 }.orZero() +
            (importedEngagements?.takeIf { useImportedEngagements } ?: 0L)
        val engagementPosts = livePosts.toLong() + importedPosts.takeIf { useImportedEngagements }.orZero()

        val liveRateViews = liveViews.takeIf { it > 0 } ?: 0L
        val liveRateEngagements = liveEngagements.takeIf { liveRateViews > 0 } ?: 0L
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
