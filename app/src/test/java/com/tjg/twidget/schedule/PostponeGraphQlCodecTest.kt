package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostponeGraphQlCodecTest {
    @Test
    fun schedulePayloadMatchesDocumentedTweetInput() {
        val post = ScheduledPost(
            provider = ScheduleProvider.POSTPONE,
            accountUsername = "@owen",
            scheduledAt = 4_000_000_000_000L,
            thread = listOf(
                ScheduleThreadItem(
                    text = "Hello",
                    media = listOf(PublicUrlMedia("https://example.com/image.jpg")),
                )
            ),
        )

        val root = JsonText.parse(
            PostponeGraphQlCodec.tweetMutationRequest(post, update = false)
        ).asObject()
        val input = root.objectValue("variables").objectValue("input")
        val tweet = input.array("thread").values.single().asObject()

        assertEquals("ScheduleTweet", root.string("operationName"))
        assertEquals("owen", input.string("username"))
        assertEquals("READY_TO_PUBLISH", input.string("publishingStatus"))
        assertEquals("Hello", tweet.string("text"))
        assertEquals(
            "https://example.com/image.jpg",
            tweet.array("mediaUrls").values.single().let { (it as JsonValue.StringValue).value },
        )
    }

    @Test
    fun localUriIsRejectedInsteadOfPretendingPostponeCanUploadIt() {
        val post = ScheduledPost(
            provider = ScheduleProvider.POSTPONE,
            accountUsername = "owen",
            scheduledAt = 4_000_000_000_000L,
            thread = listOf(
                ScheduleThreadItem(
                    text = "Hello",
                    media = listOf(LocalUriMedia("content://media/1")),
                )
            ),
        )

        assertTrue(
            runCatching { PostponeGraphQlCodec.tweetMutationRequest(post, update = false) }.isFailure
        )
    }

    @Test
    fun parsesSuccessfulMutationAndFieldErrors() {
        val success = PostponeGraphQlCodec.parseMutation(
            """
            {"data":{"scheduleTweet":{"success":true,"errors":[],"post":{"id":"post-123","submissions":[{"id":"submission-123"}]}}}}
            """.trimIndent(),
            "scheduleTweet",
        )
        val failure = PostponeGraphQlCodec.parseMutation(
            """
            {"data":{"scheduleTweet":{"success":false,"errors":[
              {"field":"text","message":"Tweet is too long"}
            ],"post":null}}}
            """.trimIndent(),
            "scheduleTweet",
        )

        assertTrue(success.isSuccess)
        assertEquals("post-123", success.value?.remotePostId)
        assertEquals("submission-123", success.value?.remoteSubmissionId)
        assertFalse(failure.isSuccess)
        assertEquals("text", failure.errors.single().field)
        assertEquals("Tweet is too long", failure.errors.single().message)
    }

    @Test
    fun surfacesTopLevelGraphQlErrors() {
        val result = PostponeGraphQlCodec.parseProfile(
            """{"data":null,"errors":[{"message":"Invalid API key"}]}"""
        )

        assertFalse(result.isSuccess)
        assertEquals("Invalid API key", result.errors.single().message)
    }

    @Test
    fun parsesAccountsAndContentLibraryPages() {
        val accounts = PostponeGraphQlCodec.parseSocialAccounts(
            """
            {"data":{"socialAccounts":[
              {"id":"1","username":"owen","formattedUsername":"@owen","name":"Owen",
               "platform":"twitter","avatarUrl":null,"isConnected":true,"isEnabled":true},
              {"id":"2","username":"other","formattedUsername":"other","name":"Other",
               "platform":"reddit","avatarUrl":null,"isConnected":true,"isEnabled":true}
            ]}}
            """.trimIndent()
        )
        val media = PostponeGraphQlCodec.parseMedia(
            """
            {"data":{"media":{"total":1,"objects":[
              {"id":"m1","name":"photo.jpg","url":"https://cdn.example/photo.jpg",
               "thumbnailUrl":null,"mimeType":"image/jpeg","size":42}
            ]}}}
            """.trimIndent()
        )

        assertEquals(listOf("owen"), accounts.value?.map { it.username })
        assertEquals(1, media.value?.total)
        assertEquals(42L, media.value?.items?.single()?.size)
    }

    @Test
    fun requestsAndParsesScheduledAndDraftSubmissions() {
        val request = JsonText.parse(
            PostponeGraphQlCodec.submissionsRequest(
                "account-1",
                PostponePublishingStatus.DRAFT,
                PostponeSubmissionType.ALL,
                page = 2,
                limit = 100,
                startDateMillis = 1_893_456_000_000L,
            )
        ).asObject()
        val variables = request.objectValue("variables")
        assertEquals("DRAFT", variables.string("publishingStatus"))
        assertEquals("ALL", variables.string("submissionType"))
        assertEquals(2L, variables.long("page"))
        assertEquals("2030-01-01T00:00:00Z", variables.string("startDate"))

        val result = PostponeGraphQlCodec.parseSubmissions(
            """
            {"data":{"twitterSubmissions":{"total":1,"objects":[{
              "id":"submission-1","text":"Remote draft","postAt":"2030-01-02T03:04:05Z",
              "result":{"dateSubmitted":"2030-01-02T03:05:06Z"},
              "error":null
            }]}}}
            """.trimIndent()
        )

        assertTrue(result.isSuccess)
        assertEquals("submission-1", result.value?.submissions?.single()?.id)
        assertEquals(1893553445000L, result.value?.submissions?.single()?.postAt)
        assertEquals(1893553506000L, result.value?.submissions?.single()?.submittedAt)
    }
}
