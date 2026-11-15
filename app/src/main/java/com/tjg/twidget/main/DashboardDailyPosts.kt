package com.tjg.twidget.main

import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar-day deltas require both that day's close and the preceding day's close. */
internal object DashboardDailyPosts {
    data class Point(val timestamp: Long, val net: Long?)
    fun points(history: List<HistorySample>, stats: ProfileStats? = null,
        today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Point> {
        val closes = history.filter { it.postsKnown && !it.estimated && it.timestamp > 0 }
            .sortedBy { it.timestamp }.associate {
                Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() to it.posts
            }.toMutableMap()
        if (stats != null && stats.postsKnown && stats.syncedAt > 0) {
            closes[Instant.ofEpochMilli(stats.syncedAt).atZone(zone).toLocalDate()] = stats.statusesCount
        }
        return (6L downTo 0L).map { offset ->
            val day = today.minusDays(offset)
            val current = closes[day]
            val baseline = closes[day.minusDays(1)]
            Point(day.atStartOfDay(zone).toInstant().toEpochMilli(),
                if (current != null && baseline != null) current - baseline else null)
        }
    }
}
