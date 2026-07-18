package com.tjg.twidget.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleQueuePolicyTest {
    @Test
    fun localQueueOnlyIncludesDefaultAccountLocalDrafts() {
        val defaultLocal = post(ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", "thatjoshguy69")
        val otherLocal = post(ScheduleProvider.LOCAL_REMINDER, "kingowenfyi", "kingowenfyi")
        val bufferPost = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")

        assertTrue(ScheduleQueuePolicy.includes(defaultLocal, ScheduleProvider.LOCAL_REMINDER, "@thatjoshguy69", null))
        assertFalse(ScheduleQueuePolicy.includes(otherLocal, ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", null))
        assertFalse(ScheduleQueuePolicy.includes(bufferPost, ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", null))
    }

    @Test
    fun bufferQueueUsesTheMappedBufferChannel() {
        val selectedChannel = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")
        val otherChannel = post(ScheduleProvider.BUFFER, "thatjoshguy69", "other-channel")
        val localPost = post(ScheduleProvider.LOCAL_REMINDER, "thatjoshguy69", "thatjoshguy69")

        assertTrue(ScheduleQueuePolicy.includes(selectedChannel, ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel"))
        assertFalse(ScheduleQueuePolicy.includes(otherChannel, ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel"))
        assertFalse(ScheduleQueuePolicy.includes(localPost, ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel"))
    }

    @Test
    fun publishedNotificationOnlyFiresOnScheduledBufferTransition() {
        val scheduled = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")
            .copy(status = ScheduleStatus.SCHEDULED)
        val draft = scheduled.copy(status = ScheduleStatus.DRAFT)
        val local = scheduled.copy(provider = ScheduleProvider.LOCAL_REMINDER)

        assertTrue(ScheduleNotificationPolicy.shouldNotifyBufferPublished(scheduled, ScheduleStatus.PUBLISHED))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(draft, ScheduleStatus.PUBLISHED))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(local, ScheduleStatus.PUBLISHED))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferPublished(scheduled, ScheduleStatus.SCHEDULED))
    }

    private fun post(provider: ScheduleProvider, accountId: String, accountUsername: String) = ScheduledPost(
        provider = provider,
        accountId = accountId,
        accountUsername = accountUsername,
        scheduledAt = 1L,
        thread = listOf(ScheduleThreadItem(text = "Draft")),
    )
}
