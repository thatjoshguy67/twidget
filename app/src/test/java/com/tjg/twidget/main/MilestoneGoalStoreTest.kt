package com.tjg.twidget.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MilestoneGoalStoreTest {
    @Test
    fun keepsOneGoalPerMetricWhileAllowingDistinctFollowerMetrics() {
        val goals = MilestoneGoalStore.normalize(
            listOf(
                goal(MilestoneMetric.FOLLOWERS, 8_000.0),
                goal(MilestoneMetric.VERIFIED_FOLLOWERS, 500.0),
                goal(MilestoneMetric.FOLLOWERS, 10_000.0),
            ),
        )

        assertEquals(
            listOf(MilestoneMetric.FOLLOWERS, MilestoneMetric.VERIFIED_FOLLOWERS),
            goals.map(AccountGoalSettings::metric),
        )
        assertEquals(10_000.0, goals.first().target, 0.0)
    }

    @Test
    fun dropsUnconfiguredAndInvalidGoals() {
        val goals = MilestoneGoalStore.normalize(
            listOf(
                goal(MilestoneMetric.FOLLOWERS, 0.0),
                goal(MilestoneMetric.ENGAGEMENT_RATE, 0.05).copy(configured = false),
            ),
        )

        assertTrue(goals.isEmpty())
    }

    private fun goal(metric: MilestoneMetric, target: Double) = AccountGoalSettings(
        configured = true,
        metric = metric,
        target = target,
    )
}
