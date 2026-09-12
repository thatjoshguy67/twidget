package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferScheduleSyncPolicyTest {
    private val now = 1_000_000L

    @Test
    fun dueScheduledPostIsRetainedUntilBufferReportsTerminalStatus() {
        val post = scheduledPost(scheduledAt = now - 1_000L)

        assertFalse(BufferScheduleSync.shouldRemoveMissing(post, emptySet(), now))
    }

    @Test
    fun nearDueScheduledPostIsRetainedAcrossBufferConsistencyWindow() {
        val post = scheduledPost(scheduledAt = now + 60_000L)

        assertFalse(BufferScheduleSync.shouldRemoveMissing(post, emptySet(), now))
    }

    @Test
    fun genuinelyRemovedFutureScheduleIsRemovedLocally() {
        val post = scheduledPost(scheduledAt = now + 10 * 60_000L)

        assertTrue(BufferScheduleSync.shouldRemoveMissing(post, emptySet(), now))
    }

    @Test
    fun remotePostStillReturnedByBufferIsNeverRemoved() {
        val post = scheduledPost(scheduledAt = now + 10 * 60_000L)

        assertFalse(BufferScheduleSync.shouldRemoveMissing(post, setOf("remote-1"), now))
    }

    @Test
    fun overdueScheduledAndSendingStatusesAwaitConfirmation() {
        assertEquals(
            ScheduleStatus.AWAITING_CONFIRMATION,
            BufferScheduleSync.resolvedStatus("scheduled", now - 1L, now),
        )
        assertEquals(
            ScheduleStatus.AWAITING_CONFIRMATION,
            BufferScheduleSync.resolvedStatus("sending", now, now),
        )
    }

    @Test
    fun explicitBufferErrorOverridesTheDueTimeFallback() {
        assertEquals(
            ScheduleStatus.NEEDS_ACTION,
            BufferScheduleSync.resolvedStatus("error", now - 1L, now),
        )
    }

    @Test
    fun futureScheduledPostRemainsUpcoming() {
        assertEquals(
            ScheduleStatus.SCHEDULED,
            BufferScheduleSync.resolvedStatus("scheduled", now + 1L, now),
        )
    }

    @Test
    fun unconfirmedPostKeepsCheckingAfterMoreThanADayOffline() {
        val post = scheduledPost(scheduledAt = now - 1_000L).copy(
            status = ScheduleStatus.AWAITING_CONFIRMATION,
            publishedAt = now - 1_000L,
        )

        assertEquals(now - 1_000L, BufferScheduleSync.terminalConfirmationTime(post, now))
        assertEquals(
            now - 1_000L,
            BufferScheduleSync.terminalConfirmationTime(post, now + 3 * 24 * 60 * 60 * 1000L),
        )
    }

    private fun scheduledPost(scheduledAt: Long) = ScheduledPost(
        provider = ScheduleProvider.BUFFER,
        status = ScheduleStatus.SCHEDULED,
        accountUsername = "buffer-channel",
        scheduledAt = scheduledAt,
        thread = listOf(ScheduleThreadItem(text = "Scheduled post")),
        remotePostId = "remote-1",
    )
}
