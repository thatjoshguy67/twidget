package com.tjg.twidget.schedule

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

    private fun scheduledPost(scheduledAt: Long) = ScheduledPost(
        provider = ScheduleProvider.BUFFER,
        status = ScheduleStatus.SCHEDULED,
        accountUsername = "buffer-channel",
        scheduledAt = scheduledAt,
        thread = listOf(ScheduleThreadItem(text = "Scheduled post")),
        remotePostId = "remote-1",
    )
}
