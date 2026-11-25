package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostSummary
import org.junit.Assert.*
import org.junit.Test

class BriefViewsRankTest {
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L
    private fun post(id: Int, views: Long = id.toLong()) = PostSummary("https://x.com/test/status/$id", "Post", views,
        0,0,0,0,0,now - 1000 + id,"","Test","test","")
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
        val selected = post(100, 50).copy(timestamp = now - day)
        val other = post(101, 100).copy(timestamp = now - 10 * day)
        val analytics = com.tjg.twidget.analytics.AnalyticsClient.analyticsFromPosts("test", 0, 2, false,
            listOf(selected), bestCandidates = listOf(selected), cachedAt = now)
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

    @Test fun oldAllTimeWinnersCannotSkewRecentRanksOrReceiveARecentRank() {
        val recent = post(100, 100)
        val peer = post(101, 200)
        val oldBanger = post(102, 10_000_000).copy(timestamp = now - 365 * day)
        val evidence = BriefReviewEvidence(recent, emptyList(), emptyList(), now, listOf(peer, oldBanger))
        assertEquals(2, evidence.viewsRank)
        assertEquals(2, evidence.viewsRankTotal)
        val historical = evidence.copy(post = oldBanger, rankingPosts = listOf(recent, peer))
        assertNull(historical.viewsRank)
        assertEquals(0, historical.viewsRankTotal)
    }

    @Test fun postsOutsideTheLatestTenAndFuturePostsAreNotRanked() {
        val subject = post(100, 1_000_000).copy(timestamp = now - 25 * day)
        val recent = (1..10).map { post(it).copy(timestamp = now - it * day) }
        assertNull(BriefReviewEvidence(subject, emptyList(), emptyList(), now, recent).viewsRank)
        val current = post(101, 100)
        val future = post(102, 1_000_000).copy(timestamp = now + day)
        val otherAccount = post(103, 1_000_000).copy(authorUserName = "other")
        val evidence = BriefReviewEvidence(current, emptyList(), emptyList(), now, listOf(future, otherAccount))
        assertEquals(1, evidence.viewsRank)
        assertEquals(1, evidence.viewsRankTotal)
    }

    @Test fun unmeasuredRecentPostsCannotCrowdOutEligibleMeasuredPeers() {
        val selected = post(100, 100)
        val measured = post(101, 200).copy(timestamp = now - day)
        val unknown = (200..220).map { post(it).copy(viewsKnown = false) }
        val analytics = com.tjg.twidget.analytics.AnalyticsClient.analyticsFromPosts("test", 0, 1, true,
            listOf(selected), cachedAt = now).copy(reviewPosts = unknown + measured)
        val evidence = BriefReviewEvidence.create(selected, analytics, emptyList(), now)
        assertEquals(2, evidence.viewsRankTotal)
        assertEquals(2, evidence.viewsRank)
    }

}
