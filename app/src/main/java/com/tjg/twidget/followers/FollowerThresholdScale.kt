package com.tjg.twidget.followers

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Continuous travel between familiar milestones, with equal room for each order of magnitude. */
internal class FollowerThresholdScale(counts: List<Long>, selected: Long = 0) {
    val maximum = maxOf(counts.maxOrNull() ?: 0, selected, 0)
    val milestones: List<Long> = buildList {
        add(0L)
        var milestone = 100L
        while (milestone < maximum) {
            add(milestone)
            if (milestone > Long.MAX_VALUE / 10) break
            milestone *= 10
        }
        if (maximum > 0) add(maximum)
    }
    private val intervals = (milestones.size - 1).coerceAtLeast(1)
    // At ordinary follower counts, every integer remains representable. Bound
    // the native Int progress domain without overflowing for extreme archives.
    val stepsPerInterval = (milestones.zipWithNext().maxOfOrNull { (a, b) -> b - a } ?: 1L)
        .coerceIn(1, (Int.MAX_VALUE / intervals).toLong()).toInt()
    val maxProgress = if (maximum == 0L) 0 else intervals * stepsPerInterval
    val milestonePositions = milestones.indices.map { it * stepsPerInterval }

    fun valueAt(progress: Int): Long {
        if (maxProgress == 0) return 0
        val bounded = progress.coerceIn(0, maxProgress)
        if (bounded == maxProgress) return maximum
        val index = bounded / stepsPerInterval
        val fraction = (bounded % stepsPerInterval).toDouble() / stepsPerInterval
        val start = milestones[index]
        val end = milestones[index + 1]
        return (start.toDouble() + (end - start).toDouble() * fraction).roundToLong().coerceIn(start, end)
    }
    fun position(value: Long): Int {
        val bounded = value.coerceIn(0, maximum)
        if (bounded == maximum) return maxProgress
        val index = milestones.indexOfLast { it <= bounded }.coerceAtLeast(0)
        val fraction = (bounded - milestones[index]).toDouble() / (milestones[index + 1] - milestones[index])
        return (index * stepsPerInterval + fraction * stepsPerInterval).roundToInt().coerceIn(0, maxProgress)
    }
    fun snappedPosition(progress: Int, tolerance: Int): Int {
        val bounded = progress.coerceIn(0, maxProgress)
        val nearest = milestonePositions.minBy { abs(it - bounded) }
        return if (abs(nearest - bounded) <= tolerance.coerceIn(0, stepsPerInterval / 12)) nearest else bounded
    }
}
