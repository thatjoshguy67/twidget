package com.tjg.twidget.main

import android.content.Context
import com.tjg.twidget.analytics.AnalyticsBlendPolicy
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.XAnalyticsMovement
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.followers.TopFollowersArchiveStore
import com.tjg.twidget.followers.TopFollowersStore

internal data class MilestoneMetricSnapshot(
    val value: Double?,
    val history: List<Double>,
    val available: Boolean,
)

internal object MilestoneMetricResolver {
    fun resolve(
        context: Context,
        account: String,
        metric: MilestoneMetric,
        stats: ProfileStats,
        history: List<HistorySample>,
        analytics: PostAnalytics?,
        imported: List<XAnalyticsMovement>,
    ): MilestoneMetricSnapshot = when (metric) {
        MilestoneMetric.FOLLOWERS -> MilestoneMetricSnapshot(
            value = stats.followersCount.toDouble().takeIf { stats.followersKnown },
            history = history.filter { it.followersKnown }.map { it.followers.toDouble() },
            available = stats.followersKnown,
        )
        MilestoneMetric.VERIFIED_FOLLOWERS -> {
            val scan = TopFollowersStore.read(context, account)
            val archived = TopFollowersArchiveStore.readAll(context, account)
            val complete = scan.complete && archived.isNotEmpty()
            MilestoneMetricSnapshot(
                value = archived.count { it.verified }.toDouble().takeIf { complete },
                history = emptyList(),
                available = complete,
            )
        }
        MilestoneMetric.ENGAGEMENT_RATE -> {
            val blend = AnalyticsBlendPolicy.blend(analytics, imported)
            val daily = imported.mapNotNull { row ->
                val impressions = row.impressions ?: return@mapNotNull null
                val engagements = row.engagements ?: return@mapNotNull null
                if (impressions <= 0L) null else engagements.toDouble() / impressions
            }
            MilestoneMetricSnapshot(
                value = blend.engagementRate,
                history = daily,
                available = blend.engagementRate != null,
            )
        }
        MilestoneMetric.IMPRESSIONS -> {
            val daily = imported.mapNotNull { it.impressions?.toDouble() }
            val importedTotal = daily.takeIf { it.isNotEmpty() }?.sum()
            val serverTotal = analytics?.totalViews?.toDouble()?.takeIf { it > 0.0 }
            MilestoneMetricSnapshot(
                value = importedTotal ?: serverTotal,
                history = daily,
                available = importedTotal != null || serverTotal != null,
            )
        }
    }
}
