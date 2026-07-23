package com.tjg.twidget.followers

import android.content.Context
import com.tjg.twidget.bridge.BridgeLog
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.BridgeEndpoint
import com.tjg.twidget.data.TwidgetStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in exchange for completed Top Followers scans in the shared history pool. */
object TopFollowersBridgeCache {
    fun fetch(context: Context, username: String): TopFollowersState? {
        val settings = TwidgetStore.settings(context)
        if (!TopFollowersSharingPolicy.enabled(settings.shareHistory)) return null
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        val response = request(
            context,
            "GET",
            "${endpoint.url}/history/${encode(username)}/top-followers",
            null,
            endpoint,
        )
        if (response.code == 404) return null
        val body = HttpTransport.requireSuccess(response, "Top Followers cache")
        return TopFollowersBridgeCodec.decode(body)
    }

    fun publish(context: Context, username: String, state: TopFollowersState) {
        val settings = TwidgetStore.settings(context)
        if (!TopFollowersSharingPolicy.shouldPublish(settings.shareHistory, state)) return
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        // History registration is itself the user's opt-in signal. Ensure the
        // account is registered before attaching a ranking to its pool entry.
        val registration = request(
            context,
            "GET",
            "${endpoint.url}/history/${encode(username)}",
            null,
            endpoint,
        )
        HttpTransport.requireSuccess(registration, "Shared history registration")
        val response = request(
            context,
            "POST",
            "${endpoint.url}/history/${encode(username)}/top-followers",
            TopFollowersBridgeCodec.encode(state),
            endpoint,
        )
        HttpTransport.requireSuccess(response, "Top Followers cache")
    }

    private fun request(
        context: Context,
        method: String,
        url: String,
        body: String?,
        endpoint: BridgeEndpoint,
    ): HttpTransport.Response {
        val headers = buildMap {
            if (endpoint.token.isNotBlank()) {
                put("X-Rettiwt-Api-Key", endpoint.token)
                put("Authorization", "Bearer ${endpoint.token}")
            }
            if (body != null) put("Content-Type", "application/json")
        }
        val startedAt = System.currentTimeMillis()
        return try {
            val response = if (body == null) {
                HttpTransport.get(url, headers)
            } else {
                HttpTransport.post(url, body, headers)
            }
            BridgeLog.record(
                context,
                method,
                url,
                response.code,
                response.body,
                System.currentTimeMillis() - startedAt,
                requestBody = body,
            )
            response
        } catch (error: Exception) {
            BridgeLog.record(
                context,
                method,
                url,
                null,
                null,
                System.currentTimeMillis() - startedAt,
                requestBody = body,
                error = error.message,
            )
            throw error
        }
    }

    private fun encode(username: String): String =
        URLEncoder.encode(username.trim().trimStart('@'), StandardCharsets.UTF_8.name())
}

internal object TopFollowersSharingPolicy {
    fun enabled(shareHistory: Boolean): Boolean = shareHistory

    fun shouldPublish(shareHistory: Boolean, state: TopFollowersState): Boolean =
        shareHistory && state.complete && state.top.isNotEmpty()
}

internal object TopFollowersBridgeCodec {
    fun encode(state: TopFollowersState): String = JSONObject().apply {
        put("scanned", state.scanned)
        put("pages", state.pages)
        put("completedAt", state.completedAt)
        put("top", JSONArray().apply {
            state.top.take(5).forEach { follower ->
                put(JSONObject().apply {
                    put("id", follower.id)
                    put("username", follower.username)
                    put("name", follower.name)
                    put("followers", follower.followers)
                    put("verified", follower.verified)
                    put("avatar", follower.avatarUrl)
                })
            }
        })
    }.toString()

    fun decode(raw: String): TopFollowersState? = runCatching {
        val root = JSONObject(raw)
        val users = root.optJSONArray("top") ?: return null
        val top = buildList {
            for (index in 0 until users.length().coerceAtMost(5)) {
                val user = users.getJSONObject(index)
                val username = user.optString("username").trim().trimStart('@')
                if (username.isBlank()) continue
                add(TopFollower(
                    id = user.optString("id"),
                    username = username,
                    name = user.optString("name"),
                    followers = user.optLong("followers").coerceAtLeast(0L),
                    verified = user.optBoolean("verified"),
                    avatarUrl = user.optString("avatar"),
                ))
            }
        }
        if (top.isEmpty()) return null
        TopFollowersState(
            top = rankedTopFollowers(top),
            pages = root.optInt("pages").coerceAtLeast(0),
            scanned = root.optInt("scanned").coerceAtLeast(top.size),
            complete = true,
            completedAt = root.optLong("cachedAt").coerceAtLeast(0L),
        )
    }.getOrNull()
}
