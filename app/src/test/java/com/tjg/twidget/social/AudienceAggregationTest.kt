package com.tjg.twidget.social

import org.junit.Assert.*
import org.junit.Test

class AudienceAggregationTest {
    private val accounts = listOf(
        PlatformAccount("x", SocialPlatform.X, "1", "name", "Name"),
        PlatformAccount("yt", SocialPlatform.YOUTUBE, "UC1", "channel", "Channel"),
    ).associateBy { it.id }
    private val profile = SocialProfile("profile", listOf("x", "yt"), "x", "yt")
    private fun sample(id: String, value: Long?, at: Long = 100, rounded: Boolean = false) = MetricObservation(
        id, accounts.getValue(id).platform.audienceMetric, value, at, "test",
        if (rounded) MetricPrecision.ROUNDED else MetricPrecision.EXACT,
    )
    private fun total(samples: List<MetricObservation>, selected: SocialProfile = profile) =
        AudienceAggregation.total(selected, accounts, samples, 200, 100)

    @Test fun aggregatesAudienceButNotViewsAndCarriesRoundedPrecision() {
        val result = total(listOf(sample("x", 20), sample("yt", 300, rounded = true),
            sample("yt", 9999).copy(metric = SocialMetric.VIEWS)))
        assertEquals(320L, result.value)
        assertTrue(result.approximate)
        assertTrue(result.missingAccountIds.isEmpty())
    }

    @Test fun unknownIsNotZeroAndPartialTotalsHaveNoDelta() {
        val before = total(listOf(sample("x", 10), sample("yt", 100)))
        val partial = total(listOf(sample("x", 20), sample("yt", null)))
        assertNull(partial.value)
        assertEquals(20, partial.availableSubtotal)
        assertEquals(setOf("yt"), partial.missingAccountIds)
        assertNull(AudienceAggregation.delta(before, partial))
        assertEquals(20L, total(listOf(sample("x", 20), sample("yt", 0))).value)
    }

    @Test fun unavailableLatestSampleDoesNotResurrectOlderCount() {
        val result = total(listOf(sample("x", 10), sample("yt", 100), sample("yt", null, 150)))
        assertNull(result.value)
    }

    @Test fun staleFutureAndEstimatedSamplesAreExcluded() {
        val result = total(listOf(sample("x", 10, 99), sample("x", 30, 201),
            sample("yt", 100, 150).copy(estimated = true)))
        assertNull(result.value)
        assertEquals(setOf("x", "yt"), result.missingAccountIds)
    }

    @Test fun linkingOrRelinkingDoesNotCountAsGrowth() {
        val samples = listOf(sample("x", 20), sample("yt", 300))
        val before = total(samples)
        val linkedAgain = total(samples, profile.copy(membershipVersion = 3))
        assertNull(AudienceAggregation.delta(before, linkedAgain))
        val standalone = total(samples, profile.copy(accountIds = listOf("x"), avatarAccountId = "x"))
        assertNull(AudienceAggregation.delta(standalone, before))
    }

    @Test fun matchingMembershipAllowsLossesAsWellAsGains() {
        val before = total(listOf(sample("x", 20), sample("yt", 300)))
        val after = total(listOf(sample("x", 15), sample("yt", 300)))
        assertEquals(-5L, AudienceAggregation.delta(before, after))
    }

    @Test(expected = ArithmeticException::class) fun overflowDoesNotWrapToNegativeAudience() {
        total(listOf(sample("x", Long.MAX_VALUE), sample("yt", 1)))
    }
}
