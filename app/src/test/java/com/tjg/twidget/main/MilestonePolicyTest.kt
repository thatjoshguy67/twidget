package com.tjg.twidget.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MilestonePolicyTest {
    @Test
    fun parsesNumericInput() {
        val parsed = MilestonePolicy.parseInput("100")
        assertTrue(parsed.valid)
        assertEquals(100L, parsed.target)
    }

    @Test
    fun parsesWordInput() {
        val parsed = MilestonePolicy.parseInput("One Hundred")
        assertTrue(parsed.valid)
        assertEquals(100L, parsed.target)
    }

    @Test
    fun rejectsInvalidInput() {
        assertFalse(MilestonePolicy.parseInput("not-a-number").valid)
    }

    @Test
    fun autoDisplayUsesNumbersForNumericInput() {
        val display = MilestonePolicy.formatDisplay(
            target = 100L,
            labelRaw = "100",
            compactNumber = { it.toString() },
        )
        assertEquals("100", display)
    }

    @Test
    fun autoDisplayUsesWordsForWordInput() {
        val display = MilestonePolicy.formatDisplay(
            target = 100L,
            labelRaw = "One Hundred",
            compactNumber = { it.toString() },
        )
        assertEquals("One Hundred", display)
    }

    @Test
    fun fallsBackToAutoMilestoneWhenCustomMissing() {
        val spec = MilestonePolicy.resolveCardSpec(
            followersCount = 950L,
            followersKnown = true,
            settings = MilestoneSettings(),
            autoNextMilestone = { 1000L },
            autoPreviousMilestone = { 900L },
            compactNumber = { it.toString() },
            goalReachedText = "done",
            unknownFollowersText = "unknown",
            toNextMilestone = { remaining, target -> "$remaining to $target" },
            milestoneLabel = "Milestone",
        )
        assertEquals("1000", spec.value)
        assertEquals("50 to 1000", spec.detail)
    }

    @Test
    fun hidesProgressWhenShowPercentDisabled() {
        val spec = MilestonePolicy.resolveCardSpec(
            followersCount = 500L,
            followersKnown = true,
            settings = MilestoneSettings(target = 1000L, labelRaw = "1000", showPercent = false),
            autoNextMilestone = { 1000L },
            autoPreviousMilestone = { 900L },
            compactNumber = { it.toString() },
            goalReachedText = "done",
            unknownFollowersText = "unknown",
            toNextMilestone = { remaining, target -> "$remaining to $target" },
            milestoneLabel = "Milestone",
        )
        assertEquals(null, spec.progress)
    }

    @Test
    fun rejectsTargetBelowCurrentFollowersWhenKnown() {
        assertFalse(MilestonePolicy.isTargetAboveFollowers(999L, 1_000L, followersKnown = true))
        assertTrue(MilestonePolicy.isTargetAboveFollowers(1_000L, 1_000L, followersKnown = true))
        assertTrue(MilestonePolicy.isTargetAboveFollowers(500L, 1_000L, followersKnown = false))
    }

    @Test
    fun classifiesAccelerationButDoesNotTreatSlowerGrowthAsMovingAway() {
        assertEquals(
            MilestonePerformanceState.ACCELERATING,
            MilestonePolicy.performanceState(listOf(100.0, 101.0, 102.0, 106.0, 112.0)),
        )
        assertEquals(
            MilestonePerformanceState.NEUTRAL,
            MilestonePolicy.performanceState(listOf(100.0, 108.0, 114.0, 116.0, 117.0)),
        )
    }

    @Test
    fun classifiesRecentLossAsDecelerating() {
        assertEquals(
            MilestonePerformanceState.DECELERATING,
            MilestonePolicy.performanceState(listOf(100.0, 104.0, 107.0, 105.0, 103.0)),
        )
    }

    @Test
    fun insufficientOrSteadyHistoryIsNeutral() {
        assertEquals(
            MilestonePerformanceState.NEUTRAL,
            MilestonePolicy.performanceState(listOf(100.0, 101.0, 102.0)),
        )
        assertEquals(
            MilestonePerformanceState.NEUTRAL,
            MilestonePolicy.performanceState(listOf(100.0, 102.0, 104.0, 106.0, 108.0)),
        )
    }

    @Test
    fun messageChoiceIsStableForSameAccountDayAndState() {
        val first = MilestonePolicy.deterministicMessageIndex(
            account = "@example",
            epochDay = 20_000,
            state = MilestonePerformanceState.ACCELERATING,
            progressBand = 2,
            optionCount = 5,
        )
        val rebound = MilestonePolicy.deterministicMessageIndex(
            account = "example",
            epochDay = 20_000,
            state = MilestonePerformanceState.ACCELERATING,
            progressBand = 2,
            optionCount = 5,
        )
        assertEquals(first, rebound)
    }

    @Test
    fun progressHonoursUnknownAndBounds() {
        assertEquals(null, MilestonePolicy.progress(null, 100.0))
        assertEquals(50, MilestonePolicy.progress(50.0, 100.0))
        assertEquals(100, MilestonePolicy.progress(150.0, 100.0))
    }
}
