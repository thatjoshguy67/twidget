package com.tjg.twidget.analytics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HistoricalPostArchiveTest {
    private val now = 1_800_000_000_000L
    private fun post(id: String, views: Long, age: Long = 30 * 86_400_000L) = PostSummary("https://x.com/example/status/$id", "Stored", views, 1,0,0,0,1,now-age,"","Example","example", "", observedAt=now)
    @Test fun storedPostsRetainIdentityAndObservationTimeWithoutChangingWeeklyTotals() {
        val recent = post("1", 100, 86_400_000L)
        val old = post("2", 900)
        val weekly = AnalyticsClient.analyticsFromPosts("example", 100,1,false,listOf(recent),cachedAt=now).copy(historicalPosts=listOf(old,recent))
        val stats = HistoricalPostArchive.forStats(weekly)!!
        assertEquals(2, stats.postsAnalyzed)
        assertEquals(1000L, stats.totalViews)
        assertEquals(100L, weekly.totalViews)
        assertEquals(500.0, AnalyticsBlendPolicy.blend(weekly,emptyList()).avgViews!!, .001)
        assertNotNull(PostAgeComparison.age(old, weekly))
        val cachedWeek = weekly.copy(cachedAt = now - 3 * 86_400_000L, historicalCheckedAt = now)
        assertNotNull(PostAgeComparison.age(old, cachedWeek))
        assertEquals(1, cachedWeek.postsAnalyzed)
        val retained = weekly.copy(reviewPosts = ReviewPostRetention.merge(weekly, null))
        assertTrue(com.tjg.twidget.brief.BriefPostCoach.reviewablePosts(retained, "example", now).any { it.url == old.url })
        val root = JSONObject().put("userName","example").put("userId","123").put("updatedAt",now)
            .put("posts",org.json.JSONArray().put(AnalyticsClient.serializePost(old)))
        assertEquals(now, HistoricalPostArchive.parse(root,"example","123").single().observedAt)
        assertTrue(HistoricalPostArchive.parse(root,"example","999").isEmpty())
        assertTrue(HistoricalPostArchive.parse(root,"other","123").isEmpty())
        val restored = com.tjg.twidget.core.NetworkResponseParsers.parseBridgeAnalytics(AnalyticsClient.serialize(weekly))
        assertEquals(listOf(old,recent), restored.historicalPosts)
    }

    @Test fun coverageStopsAtThirtyDaysOrExhaustionAndSurvivesTheLocalCache() {
        val root = JSONObject().put("postCount", 4).put("replyCount", 8)
            .put("backfill", JSONObject().put("status", "partial"))
            .put("coverage", JSONObject().put("earliestPostAt", now - 8 * 86_400_000L)
                .put("replyCoverage", "reply_inclusive_timeline"))
        val coverage = HistoricalPostArchive.coverage(root)
        assertTrue(coverage.needsBackfill(now))
        assertTrue(coverage.copy(earliestPostAt = now - 500 * 86_400_000L).needsBackfill(now))
        assertFalse(coverage.copy(scannedThroughAt = now - 31 * 86_400_000L).needsBackfill(now))
        assertFalse(coverage.copy(status = "exhausted").needsBackfill(now))
        assertFalse(coverage.copy(status = "stalled").needsBackfill(now))
        val weekly = AnalyticsClient.analyticsFromPosts("example", 100, 0, true, emptyList(), cachedAt = now)
            .copy(archiveCoverage = coverage)
        val restored = com.tjg.twidget.core.NetworkResponseParsers.parseBridgeAnalytics(AnalyticsClient.serialize(weekly))
        assertEquals(coverage, restored.archiveCoverage)
    }

    @Test fun recoveredRecentPostsFillWeeklyGapsWhileOldRepliesStayHistorical() {
        val recent = post("1", 100, 86_400_000L)
        val oldReply = post("2", 900).copy(isReply = true, bookmarks = 8, likes = 12, engagements = 12)
        val root = JSONObject().put("userName", "example").put("userId", "123").put("updatedAt", now)
            .put("posts", org.json.JSONArray().put(AnalyticsClient.serializePost(recent)).put(AnalyticsClient.serializePost(oldReply)))
        val missing = AnalyticsClient.analyticsFromPosts("example", 100, 0, true, emptyList(), cachedAt = 0)
        val updated = HistoricalPostArchive.merge(missing, root, "example", "123", now)
        assertEquals(1, updated.postsAnalyzed)
        assertEquals(100L, updated.totalViews)
        assertEquals(recent.url, updated.best?.url)
        assertTrue(updated.recentReplies.isEmpty())
        assertEquals(8L, updated.historicalPosts.first { it.url == oldReply.url }.bookmarks)
        assertEquals(2, HistoricalPostArchive.forStats(updated)!!.historicalPosts.size)
        assertEquals(2, ReviewPostRetention.merge(updated, null).size)
        assertFalse(AnalyticsClient.serialize(updated).getJSONObject("benchmarks").has("medianShares"))
    }
}
