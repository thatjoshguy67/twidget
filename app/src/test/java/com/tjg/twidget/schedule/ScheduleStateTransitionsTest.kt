package com.tjg.twidget.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleStateTransitionsTest {
    @Test
    fun localReminderMovesFromScheduledToNeedsActionThenPublished() {
        assertTrue(ScheduleStateTransitions.canMove(ScheduleStatus.SCHEDULED, ScheduleStatus.NEEDS_ACTION))
        assertTrue(ScheduleStateTransitions.canMove(ScheduleStatus.NEEDS_ACTION, ScheduleStatus.PUBLISHED))
    }

    @Test
    fun failedSchedulesRemainEditableAndRetryable() {
        assertTrue(ScheduleStateTransitions.canMove(ScheduleStatus.FAILED, ScheduleStatus.DRAFT))
        assertTrue(ScheduleStateTransitions.canMove(ScheduleStatus.FAILED, ScheduleStatus.SCHEDULED))
    }

    @Test
    fun terminalSchedulesCannotBeReactivated() {
        assertFalse(ScheduleStateTransitions.canMove(ScheduleStatus.PUBLISHED, ScheduleStatus.SCHEDULED))
        assertFalse(ScheduleStateTransitions.canMove(ScheduleStatus.CANCELLED, ScheduleStatus.DRAFT))
    }
}
