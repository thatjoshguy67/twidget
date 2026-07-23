package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferScheduleSyncMediaTest {
    private val remote = BufferPost(
        id = "remote-1",
        channelId = "channel-1",
        text = "Media post",
        status = "scheduled",
        dueAt = 4_000_000_000_000L,
        createdAt = 3_999_999_000_000L,
        media = listOf(
            PublicUrlMedia(
                url = "https://buffer.example.com/original.mp4",
                mimeType = "video/mp4",
                previewUrl = "https://buffer.example.com/preview.jpg",
            ),
        ),
    )

    @Test
    fun previouslyImportedRecordRefreshesBufferMediaMetadata() {
        val current = ScheduledPost(
            id = BufferScheduleSync.remoteLocalId(remote.id),
            provider = ScheduleProvider.BUFFER,
            accountUsername = remote.channelId,
            scheduledAt = remote.dueAt,
            thread = listOf(
                ScheduleThreadItem(
                    text = remote.text,
                    media = listOf(PublicUrlMedia("https://buffer.example.com/original.mp4", "video/mp4")),
                ),
            ),
        )

        assertEquals(remote.media, mergeBufferThread(current, remote).single().media)
    }

    @Test
    fun locallyAttachedMediaRemainsAuthoritative() {
        val local = LocalUriMedia("content://media/local", "local.jpg", "image/jpeg")
        val current = ScheduledPost(
            id = BufferScheduleSync.remoteLocalId(remote.id),
            provider = ScheduleProvider.BUFFER,
            accountUsername = remote.channelId,
            scheduledAt = remote.dueAt,
            thread = listOf(ScheduleThreadItem(text = remote.text, media = listOf(local))),
        )

        assertEquals(listOf(local), mergeBufferThread(current, remote).single().media)
    }

    @Test
    fun remoteThreadMediaIsMergedIntoItsMatchingItem() {
        val threadedRemote = remote.copy(
            media = emptyList(),
            thread = listOf(
                BufferThreadItem("Root"),
                BufferThreadItem(
                    "Reply",
                    listOf(PublicUrlMedia("https://buffer.example.com/reply.png", "image/png")),
                ),
            ),
        )
        val current = ScheduledPost(
            provider = ScheduleProvider.BUFFER,
            accountUsername = remote.channelId,
            scheduledAt = remote.dueAt,
            thread = listOf(
                ScheduleThreadItem(id = "local-root", text = "Root"),
                ScheduleThreadItem(id = "local-reply", text = "Reply"),
            ),
        )

        val merged = mergeBufferThread(current, threadedRemote)

        assertEquals(listOf("local-root", "local-reply"), merged.map(ScheduleThreadItem::id))
        assertTrue(merged.first().media.isEmpty())
        assertEquals(threadedRemote.thread[1].media, merged[1].media)
    }

    @Test
    fun remoteThreadDoesNotOverwriteUnsavedLocalAttachment() {
        val local = LocalUriMedia("content://media/new", "new.png", "image/png")
        val threadedRemote = remote.copy(
            thread = listOf(BufferThreadItem("Root", remote.media)),
        )
        val current = ScheduledPost(
            provider = ScheduleProvider.BUFFER,
            accountUsername = remote.channelId,
            scheduledAt = remote.dueAt,
            thread = listOf(ScheduleThreadItem(id = "local-root", text = "Root", media = listOf(local))),
        )

        assertEquals(listOf(local), mergeBufferThread(current, threadedRemote).single().media)
    }
}
