package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleJsonCodecTest {
    @Test
    fun roundTripsEveryScheduleFieldAndMediaType() {
        val post = ScheduledPost(
            id = "schedule-id",
            provider = ScheduleProvider.BUFFER,
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
                        PublicUrlMedia("https://cdn.example.com/library.gif", "image/gif"),
                    ),
                )
            ),
            remotePostId = "remote-id",
            remoteSubmissionId = "submission-id",
            errorMessage = "Try again",
            createdAt = 100L,
            updatedAt = 200L,
            publishedAt = 300L,
            pinned = true,
            deletedAt = 1_700_000_000L,
        )

        assertEquals(post, ScheduleJsonCodec.decode(ScheduleJsonCodec.encode(post)))
    }

    @Test
    fun missingPinnedFieldDefaultsToFalse() {
        val post = ScheduledPost(
            id = "draft-id",
            provider = ScheduleProvider.LOCAL_REMINDER,
            status = ScheduleStatus.DRAFT,
            accountUsername = "account",
            scheduledAt = null,
            thread = listOf(ScheduleThreadItem(id = "item", text = "Draft")),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val encodedWithoutPinned = ScheduleJsonCodec.encode(post)
            .replace(",\"pinned\":false", "")

        assertEquals(post, ScheduleJsonCodec.decode(encodedWithoutPinned))
    }

    @Test
    fun missingDeletedAtFieldDefaultsToNull() {
        val post = ScheduledPost(
            id = "draft-id",
            provider = ScheduleProvider.LOCAL_REMINDER,
            status = ScheduleStatus.DRAFT,
            accountUsername = "account",
            scheduledAt = null,
            thread = listOf(ScheduleThreadItem(id = "item", text = "Draft")),
            createdAt = 1L,
            updatedAt = 1L,
        )

        assertEquals(post, ScheduleJsonCodec.decode(ScheduleJsonCodec.encode(post).replace(",\"deletedAt\":null", "")))
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

    @Test
    fun legacyPostponeSchedulesMigrateToSafeLocalReminders() {
        val legacy = """
            {"id":"legacy","provider":"POSTPONE","status":"SCHEDULED","accountId":"owen",
            "accountUsername":"owen","scheduledAt":2000000,"thread":[{"id":"item","text":"Legacy",
            "media":[{"type":"postpone_library","id":"media","name":"photo.jpg",
            "url":"https://example.com/photo.jpg","mimeType":"image/jpeg"}]}],"remotePostId":"remote",
            "remoteSubmissionId":"submission","errorMessage":null,"createdAt":100,"updatedAt":200,
            "publishedAt":null,"pinned":false,"deletedAt":null}
        """.trimIndent()

        val migrated = ScheduleJsonCodec.decode(legacy)

        assertEquals(ScheduleProvider.LOCAL_REMINDER, migrated.provider)
        assertEquals(
            PublicUrlMedia("https://example.com/photo.jpg", "image/jpeg"),
            migrated.thread.single().media.single(),
        )
    }
}
