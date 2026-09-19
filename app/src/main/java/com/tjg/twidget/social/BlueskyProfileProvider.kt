package com.tjg.twidget.social

import com.tjg.twidget.core.HttpTransport
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONObject

enum class SocialProviderError { INVALID_ACCOUNT, NOT_FOUND, RATE_LIMITED, UNAVAILABLE, INVALID_RESPONSE }

sealed interface SocialProfileResult {
    data class Success(val account: PlatformAccount, val observations: List<MetricObservation>) : SocialProfileResult
    data class Failure(val reason: SocialProviderError, val retryAfterSeconds: Long? = null) : SocialProfileResult
}

/** Public AppView lookup; a refresh uses DID so a handle change cannot redirect account history. */
object BlueskyProfileProvider {
    fun lookup(handle: String): SocialProfileResult = fetch(handle, null)

    fun refresh(account: PlatformAccount): SocialProfileResult {
        require(account.platform == SocialPlatform.BLUESKY)
        return fetch(requireNotNull(account.remoteId), account)
    }

    internal fun fetch(
        actorInput: String,
        existing: PlatformAccount?,
        now: Long = System.currentTimeMillis(),
        get: (String) -> HttpTransport.Response = { HttpTransport.get(it) },
    ): SocialProfileResult {
        val actor = if (actorInput.startsWith("did:")) actorInput else SocialPlatform.BLUESKY.normalizeHandle(actorInput)
        if (!validDid(actor) && !SocialPlatform.BLUESKY.validPublicHandle(actor)) {
            return SocialProfileResult.Failure(SocialProviderError.INVALID_ACCOUNT)
        }
        val response = try {
            get("https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=${URLEncoder.encode(actor, "UTF-8")}")
        } catch (_: IOException) {
            return SocialProfileResult.Failure(SocialProviderError.UNAVAILABLE)
        }
        if (response.code == 429) return SocialProfileResult.Failure(SocialProviderError.RATE_LIMITED,
            response.headerValues("Retry-After").firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 })
        if (response.code == 404) return SocialProfileResult.Failure(SocialProviderError.NOT_FOUND)
        if (response.code == 400) {
            val code = runCatching { JSONObject(response.body).optString("error") }.getOrDefault("")
            return SocialProfileResult.Failure(if (code in setOf("ProfileNotFound", "AccountNotFound", "AccountTakedown"))
                SocialProviderError.NOT_FOUND else SocialProviderError.INVALID_ACCOUNT)
        }
        if (response.code !in 200..299) return SocialProfileResult.Failure(SocialProviderError.UNAVAILABLE)
        return runCatching {
            val json = JSONObject(response.body)
            val did = json.getString("did")
            val handle = json.getString("handle")
            require(validDid(did))
            require(SocialPlatform.BLUESKY.validPublicHandle(handle))
            require(!validDid(actor) || actor == did) { "DID mismatch" }
            require(existing == null || existing.platform == SocialPlatform.BLUESKY && existing.remoteId == did)
            val avatar = json.optString("avatar").takeIf { url ->
                runCatching { URI(url).let { it.scheme == "https" && it.host != null && it.userInfo == null } }.getOrDefault(false)
            }.orEmpty()
            val account = PlatformAccount(existing?.id ?: UUID.randomUUID().toString(), SocialPlatform.BLUESKY,
                did, SocialPlatform.BLUESKY.normalizeHandle(handle), json.optString("displayName", handle), avatar)
            val observations = listOf(SocialMetric.FOLLOWERS to "followersCount", SocialMetric.FOLLOWING to "followsCount",
                SocialMetric.POSTS to "postsCount").map { (metric, field) ->
                // Missing or malformed counters are unavailable, not a manufactured zero.
                val raw = json.opt(field)
                val value = when (raw) {
                    is Int -> raw.toLong().takeIf { it >= 0 }
                    is Long -> raw.takeIf { it >= 0 }
                    else -> null
                }
                MetricObservation(account.id, metric, value, now, "bluesky_public")
            }
            SocialProfileResult.Success(account, observations)
        }.getOrElse { SocialProfileResult.Failure(SocialProviderError.INVALID_RESPONSE) }
    }

    private fun validDid(value: String): Boolean = value.matches(Regex("did:[a-z0-9]+:[A-Za-z0-9._:%-]+"))
}
