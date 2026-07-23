package com.tjg.twidget.data

import java.util.Calendar

object HistoryRangePolicy {
    fun historySpanDays(samples: List<HistorySample>): Int {
        val real = samples.filterNot { it.estimated }
        if (real.isEmpty()) return 0
        if (real.size == 1) return 1
        val first = startOfDay(real.minOf { it.timestamp })
        val last = startOfDay(real.maxOf { it.timestamp })
        return ((last - first) / DAY_MILLIS).toInt() + 1
    }

    fun isAvailable(range: HistoryRange, samples: List<HistorySample>): Boolean {
        if (range == HistoryRange.ALL_TIME) return true
        return historySpanDays(samples) >= range.requiredDays
    }

    fun resolveSavedRange(saved: HistoryRange, samples: List<HistorySample>): HistoryRange {
        if (isAvailable(saved, samples)) return saved
        return HistoryRange.entries
            .filter { isAvailable(it, samples) }
            .maxByOrNull { it.requiredDays }
            ?: HistoryRange.ALL_TIME
    }

    private fun startOfDay(timestamp: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
}
