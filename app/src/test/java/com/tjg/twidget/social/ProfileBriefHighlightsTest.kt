package com.tjg.twidget.social

import org.junit.Assert.*
import org.junit.Test

class ProfileBriefHighlightsTest {
    private val now = 20 * 86400000L
    private val account = PlatformAccount.create(SocialPlatform.GITHUB, "1", "creator", "Creator")
    private fun sample(metric: SocialMetric, value: Long?, age: Long = 0, precision: MetricPrecision = MetricPrecision.EXACT) =
        MetricObservation(account.id, metric, value, now - age, "github_official", precision)
    private fun select(vararg samples: MetricObservation) = ProfileBriefHighlights.select(listOf(account), samples.toList(), now)

    @Test fun currentTotalsFlatMetricsAndRoutineCountsDoNotBecomeHighlights() {
        assertTrue(select(sample(SocialMetric.STARS, 100)).isEmpty())
        assertTrue(select(sample(SocialMetric.STARS, 100, 86400000), sample(SocialMetric.STARS, 100)).isEmpty())
        assertTrue(select(sample(SocialMetric.POSTS, 0, 86400000), sample(SocialMetric.POSTS, 100)).isEmpty())
    }
    @Test fun meaningfulGainAndLossAreEligibleButTinyChangesAreNot() {
        assertEquals(12L, select(sample(SocialMetric.STARS, 100, 86400000), sample(SocialMetric.STARS, 112)).single().delta)
        assertEquals(-12L, select(sample(SocialMetric.FOLLOWERS, 100, 86400000), sample(SocialMetric.FOLLOWERS, 88)).single().delta)
        assertTrue(select(sample(SocialMetric.STARS, 100, 86400000), sample(SocialMetric.STARS, 101)).isEmpty())
    }
    @Test fun staleUnknownRoundedFutureAndMismatchedSourcesAreExcluded() {
        val baseline = sample(SocialMetric.FOLLOWERS, 100, 86400000)
        listOf(sample(SocialMetric.FOLLOWERS, null), sample(SocialMetric.FOLLOWERS, 200, precision = MetricPrecision.ROUNDED),
            sample(SocialMetric.FOLLOWERS, 200, -1000), sample(SocialMetric.FOLLOWERS, 200).copy(source = "other"),
            sample(SocialMetric.FOLLOWERS, 200, 86400001)).forEach { assertTrue(select(baseline, it).isEmpty()) }
    }
    @Test fun atMostOnePerAccountAndTwoNewSourceHighlightsRespectPreferences() {
        val samples = listOf(sample(SocialMetric.STARS, 10, 86400000), sample(SocialMetric.STARS, 30),
            sample(SocialMetric.FOLLOWERS, 10, 86400000), sample(SocialMetric.FOLLOWERS, 30))
        assertEquals(SocialMetric.FOLLOWERS, ProfileBriefHighlights.select(listOf(account), samples, now).single().metric)
        assertEquals(SocialMetric.STARS, ProfileBriefHighlights.select(listOf(account), samples, now) { _, m -> m == SocialMetric.STARS }.single().metric)
        val accounts = (1..4).map { account.copy(id = it.toString()) }
        assertEquals(2, ProfileBriefHighlights.select(accounts, accounts.flatMap { a -> samples.map { it.copy(accountId = a.id) } }, now).size)
    }
}
