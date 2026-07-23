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
}
