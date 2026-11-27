package com.tjg.twidget.data

object HistoryRangePolicy {
    fun historySpanDays(samples: List<HistorySample>): Int {
        val real = samples.filterNot { it.estimated }
        if (real.isEmpty()) return 0
        if (real.size == 1) return 1
        val zone = java.time.ZoneId.systemDefault()
        val first = java.time.Instant.ofEpochMilli(real.minOf { it.timestamp }).atZone(zone).toLocalDate()
        val last = java.time.Instant.ofEpochMilli(real.maxOf { it.timestamp }).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(first, last).toInt() + 1
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
}
