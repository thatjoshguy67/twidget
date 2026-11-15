package com.tjg.twidget.followers

import org.junit.Assert.*
import org.junit.Test

class FollowerThresholdScaleTest {
    @Test fun numericMilestonesAdaptToTheAvailableRange() {
        assertEquals(listOf(0L, 100L, 1_000L, 10_000L, 100_000L, 444_000L),
            FollowerThresholdScale(listOf(40, 444_000)).milestones)
        assertEquals(listOf(0L, 100L, 1_000L, 10_000L, 100_000L),
            FollowerThresholdScale(listOf(100_000)).milestones)
        assertEquals(listOf(0L, 7L), FollowerThresholdScale(listOf(7)).milestones)
        assertEquals(listOf(0L), FollowerThresholdScale(emptyList()).milestones)
        assertEquals(0, FollowerThresholdScale(listOf(0, 0)).maxProgress)
    }
    @Test fun arbitraryIntermediateValuesRemainSelectable() {
        val scale = FollowerThresholdScale(listOf(444_000))
        listOf(1L, 73L, 99L, 101L, 650L, 1_001L, 23_457L, 276_543L, 444_000L).forEach {
            assertEquals(it, scale.valueAt(scale.position(it)))
        }
        assertFalse(scale.milestones.contains(23_457L))
        val samples = (0..1000).map { scale.valueAt((scale.maxProgress.toLong() * it / 1000).toInt()) }
        assertTrue(samples.zipWithNext().all { (a, b) -> a <= b })
    }
    @Test fun snapsOnlyWithinSmallMilestoneZones() {
        val scale = FollowerThresholdScale(listOf(100_000))
        val milestone = scale.position(10_000)
        assertEquals(milestone, scale.snappedPosition(milestone + 50, 100))
        assertEquals(milestone, scale.snappedPosition(milestone - 50, 100))
        assertEquals(milestone + 101, scale.snappedPosition(milestone + 101, 100))
        val intermediate = scale.position(23_457)
        assertEquals(intermediate, scale.snappedPosition(intermediate, 100))
    }
    @Test fun savedThresholdAndExtremeEndpointsDoNotOverflow() {
        val empty = FollowerThresholdScale(emptyList())
        assertEquals(0L, empty.valueAt(0)); assertEquals(0, empty.position(0))
        val saved = FollowerThresholdScale(listOf(100), 23_457)
        assertEquals(23_457L, saved.maximum)
        assertEquals(23_457L, saved.valueAt(saved.position(23_457)))
        val huge = FollowerThresholdScale(listOf(Long.MAX_VALUE))
        assertTrue(huge.maxProgress > 0)
        assertEquals(Long.MAX_VALUE, huge.valueAt(huge.maxProgress))
        assertEquals(huge.maxProgress, huge.position(Long.MAX_VALUE))
        assertEquals(FollowerFilters(FollowerSort.NAME, 23_457), FollowerFilters.restored("NAME", 23_457))
    }
}
