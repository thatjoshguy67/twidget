package com.tjg.twidget.followers

import kotlin.math.ceil
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.pow

/** Logarithmic travel keeps small audiences usable alongside million-follower outliers. */
internal class FollowerThresholdScale(counts: List<Long>, selected: Long = 0) {
    private val sorted = counts.map { it.coerceAtLeast(0) }.sorted()
    val maximum = maxOf(sorted.lastOrNull() ?: 0, selected.coerceAtLeast(0))
    // Quantiles adapt to both the population size and its follower-count distribution.
    // Round down to two significant digits, then remove redundant presets.
    val presets = (listOf(0L) + listOf(.5, .8, .95).mapNotNull { fraction ->
        if (sorted.isEmpty()) null else rounded(sorted[(ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0)])
    }).distinct()
    val values: List<Long> = (listOf(0L, maximum, selected.coerceAtLeast(0)) + presets +
        (1 until 1000).map { rounded(expm1(ln1p(maximum.toDouble()) * it / 1000).toLong()) })
        .distinct().sorted()
    fun position(value: Long): Int = values.binarySearch(value).let { if (it >= 0) it else (-it - 1).coerceAtMost(values.lastIndex) }

    companion object {
        private fun rounded(value: Long): Long {
            if (value < 100) return value
            val step = 10.0.pow(floor(log10(value.toDouble())) - 1).toLong().coerceAtLeast(1)
            return value / step * step
        }
    }
}
