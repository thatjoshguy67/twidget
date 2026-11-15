package com.tjg.twidget.followers

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Discrete population-based stops for the widget-style segmented slider. */
internal class FollowerThresholdScale(counts: List<Long>, selected: Long = 0) {
    private val sorted = counts.map { it.coerceAtLeast(0) }.sorted()
    val maximum = maxOf(sorted.lastOrNull() ?: 0, selected.coerceAtLeast(0))
    // Quantiles adapt to both the population size and its follower-count distribution.
    // Round down to two significant digits, then remove redundant presets.
    val presets = (listOf(0L) + listOf(.5, .8, .95).mapNotNull { fraction ->
        if (sorted.isEmpty()) null else rounded(sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)])
    }).distinct()
    // Retain the upper endpoint and any saved custom threshold as exact stops.
    val values: List<Long> = (presets + maximum + selected.coerceAtLeast(0)).distinct().sorted()
    fun position(value: Long): Int = values.binarySearch(value).let { if (it >= 0) it else (-it - 1).coerceAtMost(values.lastIndex) }

    companion object {
        private fun rounded(value: Long): Long {
            if (value < 100) return value
            val step = 10.0.pow(floor(log10(value.toDouble())) - 1).toLong().coerceAtLeast(1)
            return value / step * step
        }
    }
}
