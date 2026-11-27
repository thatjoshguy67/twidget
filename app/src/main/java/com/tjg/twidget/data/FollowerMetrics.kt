package com.tjg.twidget.data

import java.time.Instant
import java.time.ZoneId

/** Shared observed follower comparisons. Estimates and missing values are not measurements. */
internal object FollowerMetrics {
    fun weeklyDelta(stats: ProfileStats, history: List<HistorySample>, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!stats.followersKnown || stats.followersCount < 0 || stats.syncedAt <= 0) return null
        val target = Instant.ofEpochMilli(stats.syncedAt).atZone(zone).toLocalDate().minusDays(7)
        val anchor = history.filter {
            !it.estimated && it.followersKnown && it.followers >= 0 && it.timestamp in 1..stats.syncedAt &&
                Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == target
        }.maxByOrNull { it.timestamp } ?: return null
        return stats.followersCount - anchor.followers
    }
}
