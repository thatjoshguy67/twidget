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

    @Test
    fun draftsAndReadyToPostItemsUseTheRecycleBin() {
        assertTrue(ScheduleTrashPolicy.canMoveToTrash(ScheduleStatus.DRAFT))
        assertTrue(ScheduleTrashPolicy.canMoveToTrash(ScheduleStatus.NEEDS_ACTION))
        assertFalse(ScheduleTrashPolicy.canMoveToTrash(ScheduleStatus.SCHEDULED))
    }

    @Test
    fun activeQueueItemsCanBePinnedButReadyItemsCannot() {
        assertTrue(ScheduleQueuePolicy.canPin(ScheduleStatus.DRAFT))
        assertTrue(ScheduleQueuePolicy.canPin(ScheduleStatus.SCHEDULED))
        assertTrue(ScheduleQueuePolicy.canPin(ScheduleStatus.FAILED))
        assertFalse(ScheduleQueuePolicy.canPin(ScheduleStatus.NEEDS_ACTION))
    }
}
