package com.tjg.twidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnalyticsBlendPolicyTest {
    private val today = LocalDate.of(2026, 7, 11)

    @Test
    fun `recent imported snapshots contribute sample weighted averages`() {
        val result = AnalyticsBlendPolicy.blend(
            server = server(views = 493, engagements = 29, posts = 1),
            imported = listOf(imported(today, impressions = 2_761, engagements = 97, posts = 1)),
            today = today,
        )

        assertEquals(1_627.0, result.avgViews ?: 0.0, 0.001)
        assertEquals(63.0, result.avgEngagements ?: 0.0, 0.001)
        assertEquals(126.0 / 3_254.0, result.engagementRate ?: 0.0, 0.000001)
        assertEquals(1, result.livePosts)
        assertEquals(1L, result.importedPosts)
        assertTrue(result.usesImportedViews)
    }

    @Test
    fun `stale imports age out and live averages remain`() {
        val result = AnalyticsBlendPolicy.blend(
            server = server(views = 493, engagements = 29, posts = 1),
            imported = listOf(imported(today.minusDays(7), impressions = 10_000, engagements = 500, posts = 2)),
            today = today,
        )

        assertEquals(493.0, result.avgViews ?: 0.0, 0.001)
        assertEquals(29.0, result.avgEngagements ?: 0.0, 0.001)
        assertFalse(result.usesImportedViews)
    }

    @Test
    fun `snapshot totals without created posts do not distort per post averages`() {
        val result = AnalyticsBlendPolicy.blend(
            server = server(views = 400, engagements = 20, posts = 2),
            imported = listOf(imported(today, impressions = 1_000, engagements = 50, posts = 0)),
            today = today,
        )

        assertEquals(200.0, result.avgViews ?: 0.0, 0.001)
        assertEquals(10.0, result.avgEngagements ?: 0.0, 0.001)
        assertFalse(result.usesImportedViews)
        assertTrue((result.engagementRate ?: 0.0) > 0.0)
    }

    private fun imported(
        date: LocalDate,
        impressions: Long,
        engagements: Long,
        posts: Long,
    ) = XAnalyticsMovement(
        date = date,
        newFollows = 0,
        unfollows = 0,
        impressions = impressions,
        engagements = engagements,
        postsCreated = posts,
    )

    private fun server(views: Long, engagements: Long, posts: Int) = PostAnalytics(
        userName = "test",
        followers = 100,
        postsAnalyzed = posts,
        windowDays = 7,
        totalViews = views,
        avgViews = views.toDouble() / posts,
        medianViews = views.toDouble() / posts,
        avgViewsPerFollower = 0.0,
        totalEngagements = engagements,
        avgEngagements = engagements.toDouble() / posts,
        medianEngagements = engagements.toDouble() / posts,
        avgEngagementsPerFollower = 0.0,
        engagementRate = engagements.toDouble() / views,
        best = null,
        worst = null,
        cachedAt = 0,
    )
}
