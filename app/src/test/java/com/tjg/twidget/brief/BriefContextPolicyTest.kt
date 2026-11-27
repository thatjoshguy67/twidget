package com.tjg.twidget.brief

import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class BriefContextPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val strings = TestBriefStrings()

    @Test fun relativeStandoutQualifiesWithoutViralCountsAndSelectsItsOwnPreview() {
        val standout = post("relative", views = 300, engagements = 10)
        val rawWinner = post("old", views = 800, engagements = 20, age = 6 * day)
        val data = analytics(listOf(standout, post("peer1"), post("peer2"), rawWinner)).copy(best = rawWinner)
        val card = BriefEngine.standoutPostCard(data, 1_000, strings, now)
        assertEquals(BriefCardType.POST, card?.type)
        assertEquals(standout.url, card?.actionData)
        assertEquals(standout, resolveBriefPost(card!!, data))
        assertTrue(card.body.contains("similar age"))
    }

    @Test fun sparseMismatchedOrTinyCountsDoNotBecomeRelativeStandouts() {
        val standout = post("relative", views = 300, engagements = 10)
        assertNull(BriefContextPolicy.relativeStandout(analytics(listOf(standout, post("only-peer")))))
        assertNull(BriefContextPolicy.relativeStandout(analytics(listOf(
            standout, post("old1", age = 6 * day), post("old2", age = 7 * day),
        ))))
        assertNull(BriefContextPolicy.relativeStandout(analytics(listOf(
            post("tiny", 3, 3), post("tiny1", 1, 1), post("tiny2", 1, 1),
        ))))
    }

    @Test fun relativeStandoutCanBeSupportedByEngagementsWithoutViews() {
        val post = post("engagement", 0, 8)
        assertEquals(post, BriefContextPolicy.relativeStandout(analytics(listOf(
            post, post("peer1", 0, 4), post("peer2", 0, 4),
        ))))
    }

    @Test fun flatAndSmallWeeksGetMeasuredContextWithoutGrowthClaims() {
        for (delta in listOf(-2L, 0L, 2L)) {
            val card = BriefContextPolicy.followerCard(stats(100 + delta), listOf(sample(7, 100)), strings, now)
            assertEquals(BriefCardType.FOLLOWER_CONTEXT, card?.type)
            assertEquals("Your week in followers", card?.title)
            assertTrue(card!!.body.contains("100 → ${100 + delta}"))
            assertFalse(card.body.contains("accelerat"))
        }
    }

    @Test fun partialHistoryUsesRecordedDatesWithoutPretendingToBeAWholeWeek() {
        val card = BriefContextPolicy.followerCard(stats(), listOf(sample(2, 95)), strings, now)
        assertEquals("Your recorded follower change", card?.title)
        assertTrue(card!!.body.contains("+5"))
        assertFalse(card.body.contains("week"))
    }

    @Test fun followerContextRejectsEstimatesUnknownsFutureAndStaleData() {
        val invalid = listOf(
            sample(7, 50).copy(estimated = true),
            sample(7, 50).copy(followersKnown = false),
            sample(-1, 50),
        )
        assertNull(BriefContextPolicy.followerCard(stats(), invalid, strings, now))
        assertNull(BriefContextPolicy.followerCard(stats().copy(followersKnown = false), listOf(sample(7, 50)), strings, now))
        assertNull(BriefContextPolicy.followerCard(stats().copy(syncedAt = now - 3 * day), listOf(sample(7, 50)), strings, now))
        assertNull(BriefContextPolicy.followerCard(stats().copy(syncedAt = now + day), listOf(sample(7, 50)), strings, now))
    }

    @Test fun followerContextUsesKnownRealAnchorAndAnActualObservedZero() {
        val card = BriefContextPolicy.followerCard(stats(0), listOf(
            sample(7, 10), sample(7, 500).copy(estimated = true),
        ), strings, now)
        assertTrue(card!!.body.contains("-10 (10 → 0)"))
    }

    @Test fun ordinaryPostSummaryUsesOnlyIncludedPostsAndLabelsSampledLifetimeTotals() {
        val a = post("a", 100, 5)
        val b = post("b", 200, 10, age = 6 * day)
        val data = analytics(listOf(a, b, a, post("future", age = -day), post("old", age = 9 * day)))
            .copy(isSampled = true, totalViews = 1_000_000)
        val card = BriefContextPolicy.postCard(data, strings, now)
        assertEquals(BriefCardType.POST_CONTEXT, card?.type)
        assertTrue(card!!.body.contains("included: 2"))
        assertTrue(card.body.contains("300 recorded views and 15 engagements"))
        assertTrue(card.body.contains("lifetime so far"))
        assertTrue(card.body.contains("sample"))
        assertFalse(card.body.contains("1,000,000"))
    }

    @Test fun missingPostCountersCannotBecomePartialTotalsInTheBrief() {
        val a = post("a", 100, 5)
        val b = post("b", 200, 10)
        assertNull(BriefContextPolicy.postCard(analytics(listOf(a, b.copy(viewsKnown = false))), strings, now))
        assertNull(BriefContextPolicy.postCard(analytics(listOf(a, b.copy(likesKnown = false))), strings, now))
        assertNotNull(BriefContextPolicy.postCard(analytics(listOf(a, b)), strings, now))
    }

    @Test fun emptyUnknownAndStalePostSnapshotsDoNotProduceContext() {
        val data = analytics(listOf(post("a"), post("b")))
        assertNull(BriefContextPolicy.postCard(data.copy(recentPosts = emptyList()), strings, now))
        assertNull(BriefContextPolicy.postCard(data.copy(recentPosts = listOf(post("a", 0, 0), post("b", 0, 0))), strings, now))
        assertNull(BriefContextPolicy.postCard(data.copy(cachedAt = now - 3 * day), strings, now))
        assertNull(BriefEngine.standoutPostCard(data.copy(cachedAt = now + day), 1_000, strings, now))
    }

    @Test fun ordinaryCardsRespectExistingContentSettingsAndAppearInTheSummary() {
        assertTrue(BriefContentCategory.FOLLOWERS.includes(BriefCardType.FOLLOWER_CONTEXT))
        assertTrue(BriefContentCategory.TOP_TWEET.includes(BriefCardType.POST_CONTEXT))
        val follower = BriefContextPolicy.followerCard(stats(), listOf(sample(7, 99)), strings, now)!!
        val posts = BriefContextPolicy.postCard(analytics(listOf(post("a"), post("b"))), strings, now)!!
        val summary = BriefEditorialSummary.from(listOf(follower, posts), strings)
        assertTrue(summary.body.contains(follower.body))
        assertTrue(summary.body.contains("recent-post snapshot"))
        assertEquals(2, BriefRankingPolicy.order(listOf(follower, posts), BriefRankingPolicy.Context(now)).size)
    }

    @Test fun explicitQuietPreviewCannotFallBackToAnotherPost() {
        val quiet = post("quiet", 10, 1)
        val rawWorst = post("fresh", 0, 0, age = day / 24)
        val data = analytics(listOf(quiet, post("a"), post("b"), rawWorst)).copy(worst = rawWorst)
        val card = BriefEngine.worstPostCard(data, strings, now)!!
        assertEquals(quiet, resolveBriefPost(card, data))
        assertNull(resolveBriefPost(card, data.copy(recentPosts = listOf(rawWorst))))
    }

    @Test fun contextCopyIsAvailableInEveryAppLanguage() {
        for (language in listOf("en", "de", "fr")) {
            val local = TestBriefStrings(Locale.forLanguageTag(language))
            val card = BriefContextPolicy.followerCard(stats(), listOf(sample(7, 99)), local, now)!!
            assertFalse(card.body.contains("%"))
            assertTrue(card.body.contains("99 → 100"))
            assertNotNull(BriefContextPolicy.postCard(analytics(listOf(post("a"), post("b"))), local, now))
        }
    }

    @Test fun weeklyNumbersRequireAnActualKnownAnchorSevenDaysAgo() {
        assertNull(BriefContextPolicy.weeklyFollowerDelta(stats(), listOf(sample(2, 80))))
        assertNull(BriefContextPolicy.weeklyFollowerDelta(stats(), listOf(sample(8, 80))))
        assertNull(BriefContextPolicy.weeklyFollowerDelta(stats(), listOf(sample(7, 80).copy(estimated = true))))
        assertNull(BriefContextPolicy.weeklyFollowerDelta(stats(), listOf(sample(7, 80).copy(followersKnown = false))))
        assertEquals(2L, BriefContextPolicy.weeklyFollowerDelta(stats(), listOf(sample(7, 98))))
        assertEquals(0L, BriefContextPolicy.weeklyFollowerDelta(stats(), listOf(sample(7, 100))))
    }

    private fun stats(followers: Long = 100) = ProfileStats("Person", "person", followers, 20, 30, 0, syncedAt = now)

    private fun sample(daysAgo: Int, followers: Long) = HistorySample(
        "", followers, 20, 30, 0, now - daysAgo * day,
    )

    private fun post(id: String, views: Long = 100, engagements: Long = 4, age: Long = 2 * day) = PostSummary(
        "https://x.com/person/status/$id", "Post $id", views, engagements, 0, 0, 0,
        engagements, now - age, "", "Person", "person", "",
    )

    private fun analytics(posts: List<PostSummary>) = PostAnalytics(
        userName = "person", followers = 100, postsAnalyzed = posts.size, windowDays = 7,
        totalViews = posts.sumOf { it.views }, avgViews = 100.0, medianViews = 100.0, avgViewsPerFollower = 1.0,
        totalEngagements = posts.sumOf { it.engagements }, avgEngagements = 4.0, medianEngagements = 4.0,
        avgEngagementsPerFollower = 0.04, engagementRate = 0.04,
        best = posts.maxByOrNull { it.engagements }, worst = posts.minByOrNull { it.engagements },
        cachedAt = now, recentPosts = posts,
    )
}
