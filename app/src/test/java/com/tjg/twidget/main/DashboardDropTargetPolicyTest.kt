package com.tjg.twidget.main

import org.junit.Assert.*
import org.junit.Test
import com.tjg.twidget.main.DashboardDropTargetPolicy.Bounds
import com.tjg.twidget.main.DashboardDropTargetPolicy.Target

class DashboardDropTargetPolicyTest {
    @Test fun aNormalCardRequiresMeaningfulTargetOverlap() {
        val target = Bounds(100f, 0f, 200f, 100f)
        assertEquals(0f, DashboardDropTargetPolicy.score(Bounds(49f, 0f, 149f, 100f), target), 0f)
        assertTrue(DashboardDropTargetPolicy.score(Bounds(51f, 0f, 151f, 100f), target) > 0f)
    }
    @Test fun aSmallCardCanBePlacedInsideAWideCardWithoutCoveringHalfOfIt() {
        assertTrue(DashboardDropTargetPolicy.score(Bounds(20f, 10f, 120f, 110f), Bounds(0f, 0f, 240f, 160f)) > 0f)
    }
    @Test fun aSmallCardNeedsItsCenterInsideALargerTarget() {
        val target = Bounds(100f, 0f, 400f, 200f)
        assertEquals(0f, DashboardDropTargetPolicy.score(Bounds(40f, 0f, 140f, 100f), target), 0f)
    }
    @Test fun aWideCardCanMoveOverASmallTarget() {
        assertTrue(DashboardDropTargetPolicy.score(Bounds(0f, 0f, 220f, 100f), Bounds(150f, 0f, 250f, 100f)) > 0f)
    }
    @Test fun smallMotionAndMarginalScoreChangesDoNotShuffleTargets() {
        val policy = DashboardDropTargetPolicy(6f)
        val targets = listOf(Target("a", Bounds(0f, 0f, 100f, 100f)), Target("b", Bounds(100f, 0f, 200f, 100f)))
        policy.begin(-200f, 0f, 0)
        assertEquals("a", policy.choose(Bounds(0f, 0f, 200f, 100f), targets, 0))
        assertNull(policy.choose(Bounds(5f, 0f, 205f, 100f), targets, 0))
        assertNull(policy.choose(Bounds(10f, 0f, 210f, 100f), targets, 0))
        assertEquals("b", policy.choose(Bounds(20f, 0f, 220f, 100f), targets, 0))
        assertEquals("a", policy.choose(Bounds(0f, 0f, 200f, 100f), targets, 0))
    }
    @Test fun edgeScrollingCanAdvanceTargetsWithAStationaryPointer() {
        val policy = DashboardDropTargetPolicy(6f)
        val target = listOf(Target("a", Bounds(0f, 0f, 100f, 100f)))
        policy.begin(-100f, 0f, 0)
        val bounds = Bounds(0f, 0f, 100f, 100f)
        assertEquals("a", policy.choose(bounds, target, 0))
        assertEquals("a", policy.choose(bounds, target, 20))
    }
    @Test fun anEmptyOrZeroSizedTargetIsIgnored() {
        assertEquals(0f, DashboardDropTargetPolicy.score(Bounds(0f, 0f, 100f, 100f), Bounds(0f, 0f, 0f, 0f)), 0f)
    }
}
