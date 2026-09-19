package com.tjg.twidget.social

import com.tjg.twidget.core.HttpTransport
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BlueskyProfileProviderTest {
    private val body = """{"did":"did:plc:abc123","handle":"new.bsky.social","displayName":"New name",
        "followersCount":12,"followsCount":0,"postsCount":30,"avatar":"https://cdn.bsky.app/avatar.png"}"""

    @Test fun publicLookupEncodesHandleAndMapsActualMetricNames() {
        var requested = ""
        val result = BlueskyProfileProvider.fetch(" @NEW.bsky.social ", null, 100) { url ->
            requested = url
            HttpTransport.Response(200, body)
        } as SocialProfileResult.Success
        assertEquals("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=new.bsky.social", requested)
        assertEquals("did:plc:abc123", result.account.remoteId)
        assertEquals(12L, result.observations.first { it.metric == SocialMetric.FOLLOWERS }.value)
        assertEquals(0L, result.observations.first { it.metric == SocialMetric.FOLLOWING }.value)
        assertEquals(30L, result.observations.first { it.metric == SocialMetric.POSTS }.value)
    }

    @Test fun didRefreshKeepsLocalIdentityAcrossHandleChanges() {
        val existing = PlatformAccount("local-id", SocialPlatform.BLUESKY, "did:plc:abc123", "old.bsky.social", "Old name")
        val result = BlueskyProfileProvider.fetch(existing.remoteId!!, existing, 100) {
            assertTrue(it.endsWith("actor=did%3Aplc%3Aabc123"))
            HttpTransport.Response(200, body)
        } as SocialProfileResult.Success
        assertEquals("local-id", result.account.id)
        assertEquals("new.bsky.social", result.account.handle)
    }

    @Test fun mismatchedDidAndMalformedJsonDoNotOverwriteAnAccount() {
        val existing = PlatformAccount("local", SocialPlatform.BLUESKY, "did:plc:different", "old.bsky.social", "Old")
        for (response in listOf(body, "{}", "not json")) {
            val result = BlueskyProfileProvider.fetch(existing.remoteId!!, existing, 100) { HttpTransport.Response(200, response) }
            assertEquals(SocialProfileResult.Failure(SocialProviderError.INVALID_RESPONSE), result)
        }
    }

    @Test fun unknownNegativeFractionalAndStringCountersAreNotReportedAsZero() {
        val sparse = """{"did":"did:plc:abc123","handle":"new.bsky.social","followersCount":-1,
            "followsCount":"15","postsCount":1.5,"avatar":"javascript:alert(1)"}"""
        val result = BlueskyProfileProvider.fetch("new.bsky.social", null, 100) { HttpTransport.Response(200, sparse) }
            as SocialProfileResult.Success
        assertTrue(result.observations.all { it.value == null })
        assertEquals("", result.account.avatarUrl)
    }

    @Test fun invalidHandleDoesNotMakeNetworkRequest() {
        val result = BlueskyProfileProvider.fetch("https://attacker.example/profile", null, 100) { error("Must not fetch") }
        assertEquals(SocialProfileResult.Failure(SocialProviderError.INVALID_ACCOUNT), result)
    }

    @Test fun rateLimitCarriesRetryAfterAndNetworkFailureIsTyped() {
        val limited = BlueskyProfileProvider.fetch("new.bsky.social", null, 100) {
            HttpTransport.Response(429, "", mapOf("retry-after" to listOf("60")))
        }
        assertEquals(SocialProfileResult.Failure(SocialProviderError.RATE_LIMITED, 60), limited)
        val offline = BlueskyProfileProvider.fetch("new.bsky.social", null, 100) { throw IOException("offline") }
        assertEquals(SocialProfileResult.Failure(SocialProviderError.UNAVAILABLE), offline)
    }

    @Test fun profileNotFoundIsNotAnEmptySuccessfulAccount() {
        val result = BlueskyProfileProvider.fetch("missing.bsky.social", null, 100) {
            HttpTransport.Response(400, """{"error":"ProfileNotFound"}""")
        }
        assertEquals(SocialProfileResult.Failure(SocialProviderError.NOT_FOUND), result)
    }
}
