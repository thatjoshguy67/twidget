package com.tjg.twidget.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MilestoneGoalDialogTest {
    @Test
    fun arbitraryFollowerTargetRoundTripsThroughNumericPicker() {
        val pickerValue = MilestoneGoalDialog.targetToPickerValue(
            MilestoneMetric.FOLLOWERS,
            8_537.0,
        )

        assertEquals(8_537, pickerValue)
        assertEquals(
            8_537.0,
            MilestoneGoalDialog.pickerValueToTarget(MilestoneMetric.FOLLOWERS, pickerValue),
            0.0,
        )
    }

    @Test
    fun engagementRateUsesWholePercentPickerValues() {
        val pickerValue = MilestoneGoalDialog.targetToPickerValue(
            MilestoneMetric.ENGAGEMENT_RATE,
            0.37,
        )

        assertEquals(37, pickerValue)
        assertEquals(
            0.37,
            MilestoneGoalDialog.pickerValueToTarget(MilestoneMetric.ENGAGEMENT_RATE, pickerValue),
            0.0001,
        )
    }
}
