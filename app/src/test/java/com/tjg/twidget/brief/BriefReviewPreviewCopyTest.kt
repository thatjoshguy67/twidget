package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class BriefReviewPreviewCopyTest {
    @Test fun historicalSavedReviewsKeepTheirCountsWithoutARecentRank() {
        val old = post().copy(timestamp = now - 365 * 86_400_000L)
        val copy = BriefReviewPreviewCopy.description(old, saved(old), strings, now)
        assertTrue(copy.contains("1,000 views"))
        assertFalse(copy.contains("Ranked"))
    }
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val strings = TestBriefStrings()
    private fun post() = PostSummary("https://x.com/example/status/1", "Shipped this.", 1000, 10, 3, 2, 0, 900,
        now - 48 * hour, "", "Example", "example", "", fullText = "Shipped this.")
    private fun saved(post: PostSummary = post(), peers: List<PostSummary> = emptyList(), observedAt: Long = now): BriefSavedReview {
        val evidence = BriefReviewEvidence(post, peers, emptyList(), observedAt, rankingAt = now)
        val input = BriefCoachInput("published", post.text, true, 0, BriefCoachReport(emptyList(), emptyList(), emptyList(), emptyList()),
            emptyList(), observedAt, "en", reviewEvidence = evidence)
        return BriefSavedReview(BriefReviewSubject(BriefReviewTarget("example", postUrl = post.url), input, post = post),
            BriefCoachGeneration.Success(BriefCoachFeedback("A possible cause, not a measurement.", emptyList(), null), true), now)
    }
    private fun peers(views: Long = 2000, likes: Long = 40) = (2..4).map {
        post().copy(url = "https://x.com/example/status/$it", views = views, likes = likes)
    }
    @Test fun invitationsIncludeAgeAndBecomeMeasuredCopyOnlyAfterSuccess() {
        assertEquals("You posted this 2 days ago. Review this tweet’s performance and what to try next.", BriefReviewPreviewCopy.description(post(), null, strings, now))
        assertEquals("You replied 2 days ago. Review this reply’s performance and what to try next.",
            BriefReviewPreviewCopy.description(post().copy(isReply = true), null, strings, now))
        val copy = BriefReviewPreviewCopy.description(post(), saved(peers = peers()), strings, now)
        assertEquals("You posted this 2 days ago. Your tweet had 1,000 views and 15 public interactions when reviewed. Ranked 4/4 by views. It received fewer views and interactions than your similar tweets.", copy)
        assertFalse(copy.contains("possible cause"))
        val single = post().copy(views = 1, likes = 1, replies = 0, reposts = 0, quotes = 0)
        assertTrue(BriefReviewPreviewCopy.description(single, saved(single), strings, now)
            .contains("Your tweet had 1 view and 1 public interaction when reviewed."))
        // Live counts can improve, but the summary must still describe the saved review's observation.
        assertTrue(BriefReviewPreviewCopy.description(post().copy(views = 99999), saved(), strings, now).contains("1,000 views"))
        assertTrue(BriefReviewPreviewCopy.description(post(), saved(post().copy(url = "https://x.com/example/status/9")), strings, now).contains("Review this"))
    }
    @Test fun earlyUnknownAndSparseEvidenceNeverBecomeUnsupportedPerformanceClaims() {
        val young = post().copy(timestamp = now - 2 * hour)
        assertFalse(BriefReviewPreviewCopy.description(young, saved(young, peers()), strings, now + 3 * 24 * hour).contains("similar tweets"))
        assertFalse(BriefReviewPreviewCopy.description(post(), saved(peers = peers().take(2)), strings, now).contains("similar tweets"))
        val unknown = post().copy(viewsKnown = false)
        val copy = BriefReviewPreviewCopy.description(unknown, saved(unknown), strings, now)
        assertTrue(copy.contains("15 public interactions"))
        assertTrue(copy.contains("views weren’t available"))
        assertFalse(copy.contains("1,000"))
        assertFalse(BriefReviewPreviewCopy.description(post(), saved(peers = peers().map { it.copy(authorUserName = "other") }), strings, now).contains("similar tweets"))
        assertFalse(BriefReviewPreviewCopy.description(post(), saved(peers = peers().map { it.copy(isReply = true) }), strings, now).contains("similar tweets"))
    }
    @Test fun mixedReachAndResponseStayDistinctAndZeroBaselinesDoNotBecomeGrowthClaims() {
        val copy = BriefReviewPreviewCopy.description(post(), saved(peers = peers(views = 200, likes = 40)), strings, now)
        assertTrue(copy.endsWith("It received more views, but fewer interactions than your similar tweets."))
        val zero = peers(0, 0).map { it.copy(replies = 0, reposts = 0) }
        assertFalse(BriefReviewPreviewCopy.description(post(), saved(peers = zero), strings, now).contains("similar tweets"))
    }
    @Test fun relativeTimeUsesTheAppLanguageAndDoesNotInventMissingOrFutureDates() {
        assertEquals("just now", BriefReviewPreviewCopy.relativeAge(now, now, strings))
        assertEquals("1 hour ago", BriefReviewPreviewCopy.relativeAge(now - hour, now, strings))
        assertEquals("30 minutes ago", BriefReviewPreviewCopy.relativeAge(now - hour / 2, now, strings))
        assertEquals("2 weeks ago", BriefReviewPreviewCopy.relativeAge(now - 15 * 24 * hour, now, strings))
        assertNull(BriefReviewPreviewCopy.relativeAge(0, now, strings))
        assertNull(BriefReviewPreviewCopy.relativeAge(now + 1, now, strings))
        assertTrue(BriefReviewPreviewCopy.description(post(), null, TestBriefStrings(Locale.GERMAN), now).startsWith("Du hast diesen Tweet vor 2 Tagen gepostet."))
        assertTrue(BriefReviewPreviewCopy.description(post(), null, TestBriefStrings(Locale.FRENCH), now).startsWith("Tu as publié ce tweet il y a 2 jours."))
    }
}
