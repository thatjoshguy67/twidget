package com.tjg.twidget.data

import java.util.Calendar
import kotlin.math.roundToLong

/** Builds a weekday-aligned account baseline for the visible week. */
object AccountAverageSeries {
    fun values(
        allHistory: List<HistorySample>,
        visibleWeek: List<HistorySample>,
        selector: (HistorySample) -> Long,
    ): List<Long> {
        if (visibleWeek.isEmpty()) return emptyList()

        val firstVisibleTimestamp = visibleWeek.minOf(HistorySample::timestamp)
        val historical = allHistory
            .asSequence()
            .filterNot(HistorySample::estimated)
            .filter { it.timestamp < firstVisibleTimestamp }
            .toList()
        if (historical.isEmpty()) return emptyList()

        val byWeekday = historical.groupBy { weekday(it.timestamp) }
        return visibleWeek.map { visible ->
            val comparable = byWeekday[weekday(visible.timestamp)].orEmpty()
            // Sparse bridge archives may not contain every weekday. Falling
            // back to the whole historical window keeps the baseline complete
            // without inventing any new observations.
            val samples = comparable.ifEmpty { historical }
            samples.map(selector).average().roundToLong()
        }
    }

    private fun weekday(timestamp: Long): Int = Calendar.getInstance().run {
        timeInMillis = timestamp
        get(Calendar.DAY_OF_WEEK)
    }

}
