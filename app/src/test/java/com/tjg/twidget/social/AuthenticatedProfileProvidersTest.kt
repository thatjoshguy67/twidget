package com.tjg.twidget.social

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AuthenticatedProfileProvidersTest {
    @Test fun youtubeKeepsRoundedSubscriberPrecisionAndMissingCounters() {
        val result = AuthenticatedProfileProviders.parse(SocialPlatform.YOUTUBE, JSONObject("""{"items":[{"id":"UC123","snippet":{"title":"Channel","customUrl":"@creator"},"statistics":{"subscriberCount":"12300","videoCount":"0"}}]}""")) as SocialProfileResult.Success
        assertEquals("UC123", result.account.remoteId)
        assertEquals(12300L, result.observations.first { it.metric == SocialMetric.SUBSCRIBERS }.value)
        assertEquals(MetricPrecision.ROUNDED, result.observations.first().precision)
        assertEquals(0L, result.observations.first { it.metric == SocialMetric.VIDEOS }.value)
        assertNull(result.observations.first { it.metric == SocialMetric.VIEWS }.value)
    }
    @Test fun hiddenSubscribersAreUnavailable() {
        val result = AuthenticatedProfileProviders.parse(SocialPlatform.YOUTUBE, JSONObject("""{"items":[{"id":"UC123","snippet":{},"statistics":{"subscriberCount":"1000","hiddenSubscriberCount":true}}]}""")) as SocialProfileResult.Success
        assertNull(result.observations.first().value)
    }
    @Test fun missingChannelDoesNotCreateAnAccount() {
        assertEquals(SocialProfileResult.Failure(SocialProviderError.NOT_FOUND), AuthenticatedProfileProviders.parse(SocialPlatform.YOUTUBE, JSONObject("""{"items":[]}""")))
    }
    @Test fun githubIdentityIsNumericIdAndUnavailableTotalsAreNotZero() {
        val result = AuthenticatedProfileProviders.parse(SocialPlatform.GITHUB, JSONObject("""{"id":7,"login":"old-name","followers":0,"following":-1,"public_repos":12,"avatar_url":"http://unsafe.test/avatar"}""")) as SocialProfileResult.Success
        assertEquals("7", result.account.remoteId)
        assertEquals("", result.account.avatarUrl)
        assertEquals(0L, result.observations.first().value)
        assertNull(result.observations.first { it.metric == SocialMetric.FOLLOWING }.value)
        assertNull(result.observations.first { it.metric == SocialMetric.STARS }.value)
    }
    @Test fun refreshCannotReplaceAuthorizedIdentity() {
        val original = PlatformAccount.create(SocialPlatform.GITHUB,"7","name","Name")
        assertEquals(SocialProfileResult.Failure(SocialProviderError.INVALID_RESPONSE), AuthenticatedProfileProviders.parse(SocialPlatform.GITHUB,
            JSONObject("""{"id":8,"login":"name","followers":500}"""), original))
    }
    @Test fun instagramDoesNotTruncateLargeIdsOrInventMetrics() {
        val result = AuthenticatedProfileProviders.parse(SocialPlatform.INSTAGRAM,
            JSONObject("""{"user_id":"17841401234567890","username":"creator","followers_count":1.5,"media_count":0}""")) as SocialProfileResult.Success
        assertEquals("17841401234567890", result.account.remoteId)
        assertNull(result.observations.first().value)
        assertEquals(0L, result.observations.last().value)
    }
}
