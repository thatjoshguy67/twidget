package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.*
import org.junit.Test

class BriefViewsRankTest {
    private fun post(id: Int, views: Long = id.toLong()) = PostSummary("https://x.com/test/status/$id", "Post", views,
        0,0,0,0,0,id.toLong(),"","Test","test","")
    @Test fun ranksAgainstAvailableDistinctMeasuredPeers() {
        val subject = post(100, 50)
        assertEquals(1, BriefReviewEvidence(subject, (1..8).map { post(it) }, emptyList(), 0).viewsRank)
        assertEquals(9, BriefReviewEvidence(subject, (1..8).map { post(it) }, emptyList(), 0).viewsRankTotal)
        assertEquals(2, BriefReviewEvidence(subject, List(9) { post(1) }, emptyList(), 0).viewsRankTotal)
        assertEquals(9, BriefReviewEvidence(subject, (1..9).map { post(it).copy(viewsKnown = it != 9) }, emptyList(), 0).viewsRankTotal)
        assertNull(BriefReviewEvidence(subject.copy(viewsKnown=false), (1..9).map { post(it) }, emptyList(), 0).viewsRank)
    }
    @Test fun usesMostRecentPeersAndSharesTies() {
        val subject = post(100, 50)
        val peers = (1..10).map { post(it, if (it <= 3) 60 else 50) } + subject
        assertEquals(3, BriefReviewEvidence(subject, peers, emptyList(), 0).viewsRank)
        assertEquals(1, BriefReviewEvidence(subject, (1..9).map { post(it, 50) }, emptyList(), 0).viewsRank)
        assertEquals(10, BriefReviewEvidence(subject, (1..9).map { post(it, 60) }, emptyList(), 0).viewsRank)
    }
    @Test fun rankingDoesNotDiscardRecentPostsBecauseTheirAgeOrFormatDiffers() {
        val selected = post(100, 50).copy(timestamp = 1L)
        val other = post(101, 100).copy(timestamp = 2L)
        val analytics = com.tjg.twidget.analytics.AnalyticsClient.analyticsFromPosts("test", 0, 2, false,
            listOf(selected), bestCandidates = listOf(selected), cachedAt = 100 * 86_400_000L)
            .copy(reviewPosts = listOf(selected, other))
        val evidence = BriefReviewEvidence.create(selected, analytics, emptyList())
        assertTrue(evidence.peers.isEmpty())
        assertEquals(2, evidence.viewsRankTotal)
        assertEquals(2, evidence.viewsRank)
    }

    @Test fun originalsAreNotRankedAgainstLowViewReplies() {
        val original = post(100, 3000)
        val originals = listOf(post(101, 44000), post(102, 2500), post(103, 2900))
        val replies = (1..10).map { post(it, 50).copy(isReply = true) }
        val evidence = BriefReviewEvidence(original, emptyList(), emptyList(), 0, replies + originals)
        assertEquals(4, evidence.viewsRankTotal)
        assertEquals(2, evidence.viewsRank)
    }

}
