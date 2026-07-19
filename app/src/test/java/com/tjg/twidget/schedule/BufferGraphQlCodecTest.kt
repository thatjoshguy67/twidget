package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferGraphQlCodecTest {
    @Test
    fun scheduledThreadUsesBufferCreatePostInput() {
        val post = ScheduledPost(
            provider = ScheduleProvider.BUFFER,
            accountUsername = "channel-123",
            scheduledAt = 4_000_000_000_000L,
            thread = listOf(
                ScheduleThreadItem(
                    text = "First",
                    media = listOf(PublicUrlMedia("https://example.com/first.jpg", "image/jpeg")),
                ),
                ScheduleThreadItem(
                    text = "Second",
                    media = listOf(PublicUrlMedia("https://example.com/clip.mp4", "video/mp4")),
                ),
            ),
        )

        val input = BufferGraphQlCodec.createPostInput(post, saveToDraft = false)

        assertEquals("channel-123", input.getString("channelId"))
        assertEquals("customScheduled", input.getString("mode"))
        assertEquals("automatic", input.getString("schedulingType"))
        assertEquals("First", input.getString("text"))
        val thread = input.getJSONObject("metadata").getJSONObject("twitter").getJSONArray("thread")
        assertEquals(2, thread.length())
        assertEquals("https://example.com/first.jpg", thread.getJSONObject(0).getJSONArray("assets")
            .getJSONObject(0).getJSONObject("image").getString("url"))
        assertEquals("https://example.com/clip.mp4", thread.getJSONObject(1).getJSONArray("assets")
            .getJSONObject(0).getJSONObject("video").getString("url"))
    }

    @Test
    fun draftFlagIsPassedToBuffer() {
        val post = ScheduledPost(
            provider = ScheduleProvider.BUFFER,
            accountUsername = "channel-123",
            scheduledAt = 4_000_000_000_000L,
            thread = listOf(ScheduleThreadItem(text = "Draft")),
        )

        assertTrue(BufferGraphQlCodec.createPostInput(post, saveToDraft = true).getBoolean("saveToDraft"))
    }

    @Test
    fun editInputUsesPostIdWithoutCreateOnlyChannelId() {
        val post = ScheduledPost(
            provider = ScheduleProvider.BUFFER,
            accountUsername = "channel-123",
            scheduledAt = 4_000_000_000_000L,
            thread = listOf(ScheduleThreadItem(text = "Updated draft")),
            remotePostId = "post-456",
        )

        val input = BufferGraphQlCodec.editPostInput(post, saveToDraft = true)

        assertEquals("post-456", input.getString("id"))
        assertTrue(!input.has("channelId"))
    }

    @Test
    fun localUriIsRejectedBecauseBufferRequiresHostedMedia() {
        val post = ScheduledPost(
            provider = ScheduleProvider.BUFFER,
            accountUsername = "channel-123",
            scheduledAt = 4_000_000_000_000L,
            thread = listOf(ScheduleThreadItem(text = "Photo", media = listOf(LocalUriMedia("content://photo")))),
        )

        assertTrue(runCatching { BufferGraphQlCodec.createPostInput(post, saveToDraft = false) }.isFailure)
    }
}
