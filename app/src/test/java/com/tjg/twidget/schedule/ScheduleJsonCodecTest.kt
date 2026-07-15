package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleJsonCodecTest {
    @Test
    fun roundTripsEveryScheduleFieldAndMediaType() {
        val post = ScheduledPost(
            id = "schedule-id",
            provider = ScheduleProvider.POSTPONE,
            status = ScheduleStatus.FAILED,
            accountId = "account-id",
            accountUsername = "owen",
            scheduledAt = 2_000_000L,
            thread = listOf(
                ScheduleThreadItem(
                    id = "item-id",
                    text = "Quotes \" and newlines\nsurvive",
                    media = listOf(
                        LocalUriMedia("content://media/1", "photo.jpg", "image/jpeg"),
                        PublicUrlMedia("https://example.com/photo.png", "image/png"),
                        PostponeLibraryMedia(
                            id = "library-id",
                            name = "library.gif",
                            url = "https://cdn.example.com/library.gif",
                            mimeType = "image/gif",
                        ),
                    ),
                )
            ),
            remotePostId = "remote-id",
            errorMessage = "Try again",
            createdAt = 100L,
            updatedAt = 200L,
            publishedAt = 300L,
        )

        assertEquals(post, ScheduleJsonCodec.decode(ScheduleJsonCodec.encode(post)))
    }

    @Test
    fun listDecoderSkipsCorruptEntriesButKeepsValidOnes() {
        val valid = ScheduledPost(
            id = "valid",
            provider = ScheduleProvider.LOCAL_REMINDER,
            accountUsername = "account",
            scheduledAt = 2_000L,
            thread = listOf(ScheduleThreadItem(id = "item", text = "Hello")),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val validObject = ScheduleJsonCodec.encode(valid)
        val decoded = ScheduleJsonCodec.decodeList("[$validObject,{\"broken\":true}]")

        assertEquals(listOf(valid), decoded)
    }

    @Test
    fun malformedDocumentIsRejectedForStoreToTreatAsEmpty() {
        assertTrue(runCatching { ScheduleJsonCodec.decodeList("not json") }.isFailure)
    }
}
