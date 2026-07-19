package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
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

    @Test
    fun failedNotificationOnlyFiresOnScheduledBufferTransition() {
        val scheduled = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel")
            .copy(status = ScheduleStatus.SCHEDULED)
        val alreadyFailed = scheduled.copy(status = ScheduleStatus.NEEDS_ACTION)
        val local = scheduled.copy(provider = ScheduleProvider.LOCAL_REMINDER)

        assertTrue(ScheduleNotificationPolicy.shouldNotifyBufferFailed(scheduled, ScheduleStatus.NEEDS_ACTION))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferFailed(alreadyFailed, ScheduleStatus.NEEDS_ACTION))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferFailed(local, ScheduleStatus.NEEDS_ACTION))
        assertFalse(ScheduleNotificationPolicy.shouldNotifyBufferFailed(scheduled, ScheduleStatus.PUBLISHED))
    }

    @Test
    fun cardMediaKeepsTheFirstFourAttachmentsAcrossAThread() {
        val media = (1..6).map { PublicUrlMedia("https://example.com/$it.jpg") }
        val post = post(ScheduleProvider.BUFFER, "thatjoshguy69", "buffer-channel").copy(
            thread = listOf(
                ScheduleThreadItem(text = "First", media = media.take(2)),
                ScheduleThreadItem(text = "Second", media = media.drop(2)),
            ),
        )

        assertEquals(media.take(4), ScheduleQueuePolicy.cardMedia(post))
    }

    private fun post(provider: ScheduleProvider, accountId: String, accountUsername: String) = ScheduledPost(
        provider = provider,
        accountId = accountId,
        accountUsername = accountUsername,
        scheduledAt = 1L,
        thread = listOf(ScheduleThreadItem(text = "Draft")),
    )
}
