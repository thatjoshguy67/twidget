package com.tjg.twidget.data

import java.util.Calendar
import kotlin.math.roundToLong

/** Builds a weekday-aligned account baseline for the visible week. */
object AccountAverageSeries {
    fun values(
        allHistory: List<HistorySample>,
        visibleWeek: List<HistorySample>,
        selector: (HistorySample) -> Long,
        allowSparseHistory: Boolean = false,
    ): List<Long> {
        if (visibleWeek.isEmpty()) return emptyList()
        if (visibleWeek.any { selector(it) <= 0L }) return emptyList()

        val firstVisibleTimestamp = visibleWeek.minOf(HistorySample::timestamp)
        val historical = allHistory
            .asSequence()
            .filterNot(HistorySample::estimated)
            .filter { it.timestamp < firstVisibleTimestamp }
            // Legacy follower-only archives could incorrectly carry `known`
            // flags for other metrics while storing zero placeholders. A
            // placeholder is not an observation and cannot form an average.
            .filter { selector(it) > 0L }
            .toList()
        if (historical.isEmpty()) return emptyList()

        val byWeekday = historical.groupBy { weekday(it.timestamp) }
        if (!allowSparseHistory && visibleWeek.any { byWeekday[weekday(it.timestamp)].isNullOrEmpty() }) {
            return emptyList()
        }
        return visibleWeek.map { visible ->
            val comparable = byWeekday[weekday(visible.timestamp)].orEmpty()
            // Sparse imported follower archives may not contain every weekday.
            // Their explicit opt-in permits a whole-window fallback; ordinary
            // live metrics must have real comparable data for every day.
            val samples = comparable.ifEmpty { historical }
            samples.map(selector).average().roundToLong()
        }
    }

    private fun weekday(timestamp: Long): Int = Calendar.getInstance().run {
        timeInMillis = timestamp
        get(Calendar.DAY_OF_WEEK)
    }

}
