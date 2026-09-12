package com.tjg.twidget.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotificationPolicyTest {
    @Test
    fun newVersionNotifiesImmediately() {
        assertTrue(UpdateNotificationPolicy.shouldNotify(
            availableVersion = "1.3.0",
            lastNotifiedVersion = "1.2.0",
            reminderVersion = null,
            remindAfter = Long.MAX_VALUE,
            now = 100L,
        ))
    }

    @Test
    fun sameVersionDoesNotNotifyEveryCheck() {
        assertFalse(UpdateNotificationPolicy.shouldNotify(
            availableVersion = "1.3.0",
            lastNotifiedVersion = "1.3.0",
            reminderVersion = null,
            remindAfter = Long.MAX_VALUE,
            now = 100L,
        ))
    }

    @Test
    fun reminderNotifiesAgainOnlyAfterItsDelay() {
        assertFalse(UpdateNotificationPolicy.shouldNotify(
            availableVersion = "1.3.0",
            lastNotifiedVersion = "1.3.0",
            reminderVersion = "1.3.0",
            remindAfter = 200L,
            now = 199L,
        ))
        assertTrue(UpdateNotificationPolicy.shouldNotify(
            availableVersion = "1.3.0",
            lastNotifiedVersion = "1.3.0",
            reminderVersion = "1.3.0",
            remindAfter = 200L,
            now = 200L,
        ))
    }
}
