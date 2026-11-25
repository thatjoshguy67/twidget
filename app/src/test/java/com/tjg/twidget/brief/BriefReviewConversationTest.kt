package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.core.NetworkResponseParsers
import org.junit.Assert.*
import org.junit.Test

class BriefReviewConversationTest {
    private fun post(id: Int, parent: Int? = null, author: String = "owner", text: String = "Post $id.") =
        PostSummary("https://x.com/$author/status/$id", text, 100, 1, 1, 0, 0, 2,
            1_800_000_000_000L + id, "", author, author, "", fullText = text, isReply = parent != null,
            conversationId = "1", replyToId = parent?.toString().orEmpty())
    private fun input() = BriefCoachInput("published", "Post 1.", true, 0,
        BriefCoachReport(emptyList(), emptyList(), emptyList(), emptyList()), emptyList(), 1_800_000_000_100L, "en-US")

    @Test fun onlyThreeOrMoreLinkedSameAuthorPostsBecomeAThread() {
        val root = post(1)
        assertFalse(BriefReviewConversations.collect(root, listOf(post(2, 1))).isThread)
        val thread = BriefReviewConversations.collect(root, listOf(post(3, 2), post(2, 1), post(7)))
        assertTrue(thread.isThread)
        assertEquals(listOf("1", "2", "3"), thread.authorPosts.map { BriefPublishedReviewStore.postId(it.url) })
        val inside = BriefReviewConversations.collect(post(2, 1), listOf(root, post(3, 2)))
        assertEquals(thread.authorPosts, inside.authorPosts)
        assertFalse(BriefReviewConversations.collect(root, listOf(post(2, 1, "other"), post(3, 2))).isThread)
        val applied = thread.apply(input())
        assertEquals("thread", applied.kind)
        assertEquals(3, applied.entries.size)
        assertTrue(applied.packet(cloud = true).data.has("threadEntries"))
    }

    @Test fun branchesAndCyclesDoNotInventAThreadOrDuplicateTweets() {
        assertFalse(BriefReviewConversations.collect(post(1), listOf(post(2, 1), post(3, 1))).isThread)
        assertFalse(BriefReviewConversations.collect(post(1, 3), listOf(post(2, 1), post(3, 2))).isThread)
        val sharedConversationOnly = listOf(post(2), post(3), post(4))
        assertFalse(BriefReviewConversations.collect(post(1), sharedConversationOnly).isThread)
    }

    @Test fun audienceReceptionExcludesSelfRepliesAndUnrelatedConversationPostsAndIsBounded() {
        val replies = listOf(post(2, 1), post(3, 99, "outsider")) + (10..40).map { post(it, 1, "reader$it", "I found the example confusing.") }
        val result = BriefReviewConversations.collect(post(1), emptyList(), replies, "sampled", "fixture")
        assertEquals(20, result.audienceReplies.size)
        assertTrue(result.audienceReplies.all { it.replyToId == "1" && it.authorUserName != "owner" })
        val packet = result.apply(input()).packet(cloud = true)
        assertTrue(packet.citations.any { it.id == "reply_0" && it.text.contains("confusing") })
        val prompt = BriefCoachAi.prompt(result.apply(input()), packet)
        assertTrue(prompt.contains("does not reveal what people thought"))
        assertTrue(prompt.contains("not evidence of no reaction"))
        assertTrue(prompt.contains("one reply page"))
    }

    @Test fun parentContextIsKeptSeparateFromAuthorThreadAndReplySentiment() {
        val parent = post(1, author = "someone")
        val result = BriefReviewConversations.collect(post(2, 1), listOf(parent))
        assertEquals(listOf(parent), result.parents)
        assertFalse(result.isThread)
        assertEquals("unavailable", result.replyCoverage)
        assertTrue(result.apply(input()).packet().citations.any { it.id == "parent_0" })
    }

    @Test fun conversationMetadataSurvivesPostAndConversationRoundTrips() {
        val p = post(2, 1)
        assertEquals(p, NetworkResponseParsers.parseBridgePost(AnalyticsClient.serializePost(p)))
        val conversation = BriefReviewConversations.collect(post(1), listOf(p, post(3, 2)), listOf(post(8, 1, "reader")), "sampled", "fixture", 55)
        assertEquals(conversation, BriefReviewConversation.decode(conversation.json()))
    }

    @Test fun sameStatisticsCannotHideChangedReplyTextButPollingTimeAloneIsNotEvidence() {
        val root = post(1)
        val a = BriefReviewConversations.collect(root, emptyList(), listOf(post(8, 1, "reader", "Great explanation.")), "sampled", "fixture", 10)
        val b = a.copy(audienceReplies = listOf(post(8, 1, "reader", "This is misleading.")))
        fun subject(c: BriefReviewConversation) = BriefReviewSubject(BriefReviewTarget("owner", postUrl = root.url), c.apply(input()), post = root)
        assertFalse(BriefReviewEvidenceFingerprint.same(subject(a), subject(b)))
        assertTrue(BriefReviewEvidenceFingerprint.same(subject(a), subject(a.copy(observedAt = 99))))
    }
}
