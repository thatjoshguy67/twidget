package com.tjg.twidget.followers

import android.content.Context
import com.tjg.twidget.bridge.BridgeLog
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.BridgeEndpoint
import com.tjg.twidget.data.TwidgetStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/** Opt-in exchange for completed Top Followers scans in the shared history pool. */
object TopFollowersBridgeCache {
    fun fetch(context: Context, username: String, completedAfter: Long = 0L): TopFollowersState? {
        val settings = TwidgetStore.settings(context)
        if (!TopFollowersSharingPolicy.enabled(settings.shareHistory)) return null
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        val previewResponse = request(
            context,
            "GET",
            "${endpoint.url}/history/${encode(username)}/top-followers",
            null,
            endpoint,
        )
        if (previewResponse.code == 404) return null
        val preview = TopFollowersBridgeCodec.decode(
            HttpTransport.requireSuccess(previewResponse, "Top Followers cache"),
        ) ?: error("Top Followers bridge returned an invalid ranking")
        if (preview.completedAt <= completedAfter) return preview
        return fetchCompleted(context, username, completedAfter)
    }

    fun startScan(context: Context, username: String): TopFollowersState {
        val settings = TwidgetStore.settings(context)
        require(TopFollowersSharingPolicy.enabled(settings.shareHistory)) { "Shared history is not enabled" }
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
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
            "${endpoint.url}/history/${encode(username)}/top-followers/scan",
            "{}",
            endpoint,
        )
        return TopFollowersBridgeCodec.decodeStatus(
            HttpTransport.requireSuccess(response, "Top Followers server scan"),
        ) ?: error("Top Followers bridge returned an invalid scan status")
    }

    fun scanStatus(context: Context, username: String): TopFollowersState {
        val settings = TwidgetStore.settings(context)
        require(TopFollowersSharingPolicy.enabled(settings.shareHistory)) { "Shared history is not enabled" }
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        val response = request(
            context,
            "GET",
            "${endpoint.url}/history/${encode(username)}/top-followers/scan",
            null,
            endpoint,
        )
        return TopFollowersBridgeCodec.decodeStatus(
            HttpTransport.requireSuccess(response, "Top Followers server scan"),
        ) ?: error("Top Followers bridge returned an invalid scan status")
    }

    fun fetchCompleted(
        context: Context,
        username: String,
        completedAfter: Long = 0L,
    ): TopFollowersState? {
        val settings = TwidgetStore.settings(context)
        if (!TopFollowersSharingPolicy.enabled(settings.shareHistory)) return null
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        var offset = 0
        var state: TopFollowersState? = null
        var pageNumber = 1
        var replacementStarted = false
        var replacementCommitted = false
        try {
            while (true) {
                val response = request(
                    context,
                    "GET",
                    "${endpoint.url}/history/${encode(username)}/top-followers/all?offset=$offset&limit=$PAGE_LIMIT",
                    null,
                    endpoint,
                )
                if (response.code == 404 && offset == 0) {
                    // Compatibility with bridges that only expose the original top-five cache.
                    val legacy = request(
                        context,
                        "GET",
                        "${endpoint.url}/history/${encode(username)}/top-followers",
                        null,
                        endpoint,
                    )
                    if (legacy.code == 404) return null
                    return TopFollowersBridgeCodec.decode(
                        HttpTransport.requireSuccess(legacy, "Top Followers cache"),
                    )
                }
                val page = TopFollowersBridgeCodec.decodePage(
                    HttpTransport.requireSuccess(response, "Top Followers cache"),
                ) ?: error("Top Followers bridge returned an invalid page")
                if (offset == 0 && page.state.completedAt <= completedAfter) {
                    return page.state.copy(top = rankedTopFollowers(page.followers, TOP_LIMIT))
                }
                if (!replacementStarted) {
                    TopFollowersArchiveStore.beginReplacement(context, username)
                    replacementStarted = true
                }
                TopFollowersArchiveStore.appendReplacement(context, username, page.followers, pageNumber++)
                val previous = state
                state = page.state.copy(
                    top = rankedTopFollowers(previous.orEmptyTop() + page.followers, TOP_LIMIT),
                )
                val next = page.nextOffset ?: break
                if (next <= offset) error("Top Followers bridge pagination stalled")
                offset = next
            }
            if (replacementStarted) {
                TopFollowersArchiveStore.commitReplacement(context, username)
                replacementCommitted = true
            }
            return state
        } finally {
            if (replacementStarted && !replacementCommitted) {
                TopFollowersArchiveStore.abortReplacement(context, username)
            }
        }
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
            val response = if (method == "POST") HttpTransport.post(url, body.orEmpty(), headers)
                else HttpTransport.get(url, headers)
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

    private fun TopFollowersState?.orEmptyTop(): List<TopFollower> = this?.top.orEmpty()

    // Large enough to hydrate normal accounts well within the bridge's public
    // request budget, while keeping each response bounded for mobile clients.
    private const val PAGE_LIMIT = 2_000
    private const val TOP_LIMIT = 100
}

internal object TopFollowersSharingPolicy {
    fun enabled(shareHistory: Boolean): Boolean = shareHistory
}

internal object TopFollowersBridgeCodec {
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

    fun decodeStatus(raw: String): TopFollowersState? = runCatching {
        val root = JSONObject(raw)
        val status = root.optString("status")
        if (status !in setOf("running", "complete", "failed")) return null
        TopFollowersState(
            pages = root.optInt("pages").coerceAtLeast(0),
            scanned = root.optInt("scanned").coerceAtLeast(0),
            scanning = status == "running",
            complete = status == "complete",
            error = root.optString("error"),
            startedAt = root.optLong("startedAt").coerceAtLeast(0L),
            completedAt = root.optLong("completedAt").coerceAtLeast(0L),
        )
    }.getOrNull()

    fun decodePage(raw: String): TopFollowersBridgePage? = runCatching {
        val root = JSONObject(raw)
        val users = root.optJSONArray("followers") ?: return null
        val followers = buildList {
            for (index in 0 until users.length()) {
                val user = users.optJSONObject(index) ?: continue
                val username = user.optString("username").trim().trimStart('@')
                if (username.isBlank()) continue
                add(TopFollower(
                    id = user.optString("id"),
                    username = username,
                    name = user.optString("name").ifBlank { username },
                    followers = user.optLong("followers").coerceAtLeast(0L),
                    verified = user.optBoolean("verified"),
                    avatarUrl = user.optString("avatar"),
                    scanIndex = user.optInt("scanIndex"),
                    mutual = if (user.has("mutual") && !user.isNull("mutual")) user.optBoolean("mutual") else null,
                ))
            }
        }
        val status = decodeStatus(raw) ?: return null
        TopFollowersBridgePage(
            state = status.copy(
                scanned = root.optInt("total", status.scanned).coerceAtLeast(followers.size),
                completedAt = root.optLong("cachedAt", status.completedAt).coerceAtLeast(0L),
            ),
            followers = followers,
            nextOffset = if (root.isNull("nextOffset")) null else root.optInt("nextOffset").takeIf { it >= 0 },
        )
    }.getOrNull()
}

internal data class TopFollowersBridgePage(
    val state: TopFollowersState,
    val followers: List<TopFollower>,
    val nextOffset: Int?,
)
