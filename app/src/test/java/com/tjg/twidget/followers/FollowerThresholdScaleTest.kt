package com.tjg.twidget.followers

import org.junit.Assert.*
import org.junit.Test

class FollowerThresholdScaleTest {
    @Test fun presetsFollowPopulationAndDistribution() {
        val ordinary = FollowerThresholdScale((1L..100L).toList())
        assertEquals(listOf(0L, 50L, 80L, 95L), ordinary.presets)
        assertEquals(listOf(0L, 50L, 80L, 95L, 100L), ordinary.values)
        assertEquals(listOf(0L, 500L, 800L, 950L), FollowerThresholdScale((1L..100L).map { it * 10 }).presets)
        val skewed = FollowerThresholdScale(List(99) { 20L } + 50_000_000L)
        assertEquals(listOf(0L, 20L), skewed.presets)
        assertEquals(50_000_000L, skewed.maximum)
        assertEquals("Only meaningful segments, including the outlier", listOf(0L, 20L, 50_000_000L), skewed.values)
    }
    @Test fun emptyZeroAndSingleFollowerAreUsable() {
        assertEquals(listOf(0L), FollowerThresholdScale(emptyList()).values)
        assertEquals(listOf(0L), FollowerThresholdScale(listOf(0, 0)).presets)
        assertEquals(listOf(0L, 7L), FollowerThresholdScale(listOf(7)).presets)
    }
    @Test fun endpointsPresetsAndSavedCustomValueAreExactAndMonotonic() {
        listOf(1L, 99L, 123_456L, Long.MAX_VALUE).forEach { maximum ->
            val scale = FollowerThresholdScale(listOf(0, maximum), 23_457)
            assertEquals(0L, scale.values.first())
            assertEquals(maxOf(maximum, 23_457), scale.values.last())
            assertTrue(scale.values.zipWithNext().all { (a, b) -> a < b })
            (scale.presets + 23_457L).forEach { assertEquals(it, scale.values[scale.position(it)]) }
        }
    }
    @Test fun restoringCustomThresholdDoesNotDiscardIt() {
        assertEquals(FollowerFilters(FollowerSort.NAME, 23_457), FollowerFilters.restored("NAME", 23_457))
        assertEquals(0L, FollowerFilters.restored(null, -5).minimum)
    }
}
