package com.tjg.twidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TwidgetAppVisibilityTest {
    @Test
    fun listenerOnlyReceivesActualForegroundTransitions() {
        val changes = mutableListOf<Boolean>()
        val registration = TwidgetAppVisibility.addVisibilityListener(changes::add)

        try {
            TwidgetAppVisibility.activityStarted()
            TwidgetAppVisibility.activityStarted()
            TwidgetAppVisibility.activityStopped()
            TwidgetAppVisibility.activityStopped()

            assertEquals(listOf(true, false), changes)
        } finally {
            registration.close()
            while (TwidgetAppVisibility.isVisible()) TwidgetAppVisibility.activityStopped()
        }
    }
}
