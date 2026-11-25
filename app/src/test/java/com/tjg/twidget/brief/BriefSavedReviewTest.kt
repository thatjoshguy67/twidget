package com.tjg.twidget.brief

import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostObservation
import com.tjg.twidget.analytics.PostSummary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BriefSavedReviewTest {
    @Test fun snapshotRoundTripsTheOriginalEvidenceMediaAndValidatedAdvice() {
        val now = 1_800_000_000_000L
        val post = PostSummary("https://x.com/example/status/123", "Finally shipped this. Thoughts?", 1000, 12, 4, 5, 2, 23,
            now - 36 * 3_600_000L, "", "Example", "example", "", fullText = "Finally shipped this. Thoughts?",
            media = listOf(PostMedia("photo", "https://example.invalid/media.png", "Comparison tool", 100, 60)),
            quotedPost = com.tjg.twidget.analytics.QuotedPost("https://x.com/source/status/9", "The source supplies the context.", "Source", "source"))
        val peers = (1..3).map { post.copy(url = "https://x.com/example/status/${123 + it}", views = 2000) }
        val analytics = AnalyticsClient.analyticsFromPosts("example", 100, 4, false, listOf(post) + peers, cachedAt = now)
        val strings = TestBriefStrings()
        val report = BriefPostCoach.review(post, analytics, strings, now)!!
        val evidence = BriefReviewEvidence.create(post, analytics, listOf(PostObservation(now, 1000, 12, 4, 5, 2)))
        val input = BriefCoachAi.review(post, analytics, report, strings, now)
            .copy(reviewEvidence = evidence, reviewCitations = evidence.citations(strings))
        val result = BriefCoachGeneration.Success(BriefCoachFeedback("Your opening may need more context.", listOf(
            BriefCoachSuggestion("Name the tool in your opening.", "The screenshot helps, but the wording could introduce it.", "Finally shipped this.",
                null, false,
                input.packet(attachedMediaIds = setOf("m0")).citations.filter { it.id in setOf("m0", "subject", "performance") })),
            null, listOf("interaction_breakdown", "history_comparison")), true, setOf("m0"), true)
        val saved = BriefSavedReview(BriefReviewSubject(BriefReviewTarget("example", postUrl = post.url), input, post = post), result, now)
        val restored = BriefSavedReviewCodec.decode(JSONObject(BriefSavedReviewCodec.encode(saved).toString()), "example")
        assertEquals(saved, restored)
        assertFalse(BriefSavedReviewCodec.encode(saved).toString().contains("apiKey"))
        assertTrue(BriefReviewFreshness.canReuse(restored, "en-US", now))
        assertFalse(BriefReviewFreshness.canReuse(restored, "fr", now))
        assertEquals(result.algorithmVersion, restored.result.algorithmVersion)
        assertFalse(BriefReviewFreshness.canReuse(restored, "en-US", now, "new-algorithm-context"))
        val legacy = BriefSavedReviewCodec.encode(saved).apply { remove("algorithmVersion") }
        val oldReview = BriefSavedReviewCodec.decode(legacy, "example")
        assertFalse(BriefReviewFreshness.canReuse(oldReview, "en-US", now))
        assertEquals(saved.result.feedback, oldReview.result.feedback)
        val conversation = BriefReviewConversations.collect(post, listOf(
            post.copy(url = "https://x.com/example/status/124", replyToId = "123", isReply = true, fullText = "More detail.", text = "More detail."),
            post.copy(url = "https://x.com/example/status/125", replyToId = "124", isReply = true, fullText = "The final takeaway.", text = "The final takeaway.")))
        val threadInput = conversation.apply(input)
        val threadResult = result.copy(feedback = result.feedback.copy(suggestions = result.feedback.suggestions.map {
            it.copy(citations = threadInput.packet(attachedMediaIds = setOf("m0")).citations.filter { citation -> citation.id in setOf("m0", "subject", "performance") })
        }))
        val threadSaved = saved.copy(subject = saved.subject.copy(input = threadInput), result = threadResult)
        val threadRestored = BriefSavedReviewCodec.decode(BriefSavedReviewCodec.encode(threadSaved), "example")
        assertEquals(conversation, threadRestored.subject.input.conversation)
        assertEquals(threadInput.entries, threadRestored.subject.input.entries)
        assertEquals(threadInput.media, threadRestored.subject.input.media)
        assertEquals("thread", threadRestored.subject.input.kind)
        val frenchThread = BriefReviewSubjects.fromSaved(threadRestored, TestBriefStrings(java.util.Locale.FRENCH))
        assertEquals("fr", frenchThread.input.language)
        assertEquals(threadInput.kind, frenchThread.input.kind)
        assertEquals(threadInput.complete, frenchThread.input.complete)
        assertEquals(threadInput.media, frenchThread.input.media)
        assertEquals(threadInput.mediaCount, frenchThread.input.mediaCount)
        assertEquals(threadInput.entries, frenchThread.input.entries)
        val french = BriefReviewSubjects.fromSaved(restored, TestBriefStrings(java.util.Locale.FRENCH))
        assertEquals("fr", french.input.language)
        assertEquals(input.text, french.input.text)
        assertEquals(input.quotedPost, french.input.quotedPost)
        assertEquals(input.reviewEvidence, french.input.reviewEvidence)
        assertNotEquals(input.report.limitations, french.input.report.limitations)
        assertNotEquals(input.reviewCitations, french.input.reviewCitations)
    }

    @Test fun identityUsesTheTweetIdAcrossHostsQueriesAndHandles() {
        assertEquals("123", BriefPublishedReviewStore.postId("https://x.com/example/status/123?s=20"))
        assertEquals("123", BriefPublishedReviewStore.postId("https://twitter.com/renamed/status/123"))
        assertEquals("123", BriefPublishedReviewStore.postId("https://x.com/i/web/status/123"))
        assertNull(BriefPublishedReviewStore.postId("https://unrelated.invalid/example/status/123"))
    }
}
