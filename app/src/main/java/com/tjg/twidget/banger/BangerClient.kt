package com.tjg.twidget.banger

import android.content.Context
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.BridgeEndpoint
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.core.NetworkResponseParsers
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class BangerResult(
    val post: PostSummary?,
    val score: Double,
    val complete: Boolean,
    val postsScanned: Int,
    val capped: Boolean = false,
)

/** Builds a durable per-account Hall of Fame without retaining full timelines. */
object BangerClient {
    private const val PREFS = "twidget_bangers"
    private const val VERSION = 4
    private const val PAGES_PER_REFRESH = 5
    private const val MAX_POSTS = 75_000
    private const val PAGE_SIZE = 100

    fun refresh(
        context: Context,
        username: String,
        settings: TwidgetSettings,
        endpoint: BridgeEndpoint,
    ): BangerResult {
        val clean = TwidgetStore.canonicalAccount(context, username)
        val generation = TwidgetStore.accountGeneration(context, clean)
        val result = if (settings.shareHistory) {
            fetchShared(context, clean, endpoint)
        } else {
            refreshLocal(context, clean, generation)
        }
        return TwidgetStore.withAccountGeneration(context, clean, generation) {
            remember(context, clean, result)
            result
        }
    }

    fun cached(context: Context, username: String): BangerResult? {
        val key = "latest:${username.trim().trimStart('@').lowercase(Locale.US)}"
        val raw = com.tjg.twidget.data.AccountPreferences.open(context, PREFS).getString(key, null) ?: return null
        return runCatching { resultFromJson(JSONObject(raw)) }.getOrNull()
    }

    // Old results remain visible while a scoring-version upgrade scans again.
    fun needsRescan(context: Context, username: String): Boolean {
        val key = "latest:${username.trim().trimStart('@').lowercase(Locale.US)}"
        val raw = com.tjg.twidget.data.AccountPreferences.open(context, PREFS).getString(key, null)
        val state = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        return needsRescan(state)
    }

    internal fun needsRescan(state: JSONObject?): Boolean = state?.optInt("version", 0) != VERSION

    fun clear(context: Context, username: String) {
        synchronized(com.tjg.twidget.data.TwidgetStore) {
            val suffix = ":${username.trim().trimStart('@').lowercase(Locale.US)}"
            val prefs = com.tjg.twidget.data.AccountPreferences.open(context, PREFS)
            val edit = prefs.edit()
            prefs.all.keys.filter { it.endsWith(suffix) }.forEach(edit::remove)
            edit.apply()
        }
    }

    private fun remember(context: Context, username: String, result: BangerResult) {
        synchronized(com.tjg.twidget.data.TwidgetStore) {
            val key = "latest:${username.trim().trimStart('@').lowercase(Locale.US)}"
            val json = JSONObject()
                .put("version", VERSION)
                .put("score", result.score)
                .put("complete", result.complete)
                .put("postsScanned", result.postsScanned)
                .put("capped", result.capped)
            result.post?.let { json.put("post", postToJson(it)) }
            com.tjg.twidget.data.AccountPreferences.open(context, PREFS).edit().putString(key, json.toString()).apply()
        }
    }

    private fun fetchShared(context: Context, username: String, endpoint: BridgeEndpoint): BangerResult {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val root = JSONObject(AnalyticsClient.read("${endpoint.url}/banger/$encoded", endpoint.token, context))
        return resultFromJson(root)
    }

    private fun refreshLocal(context: Context, username: String, generation: Long): BangerResult {
        val key = "v$VERSION:${username.lowercase(Locale.US)}"
        val prefs = com.tjg.twidget.data.AccountPreferences.open(context, PREFS)
        val state = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val previous = (prefs.getString("v3:${username.lowercase(Locale.US)}", null)
            ?: prefs.getString("v2:${username.lowercase(Locale.US)}", null))
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        var winner = (state.optJSONObject("post") ?: previous?.optJSONObject("post"))?.let(::postFromJson)
        var winnerScore = winner?.let(BangerScore::calculate) ?: 0.0
        var complete = state.optBoolean("complete", false)
        var postsScanned = state.optInt("postsScanned", 0)
        var capped = state.optBoolean("capped", false)
        // A raised MAX_POSTS lets a previously capped scan resume from its cursor.
        if (capped && postsScanned < MAX_POSTS) capped = false
        var cursor = if (complete || capped) "" else state.optString("cursor")
        val seenCursors = mutableSetOf<String>()
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val requested = username.lowercase(Locale.US)
        var fetchedAnyPage = false

        for (pageIndex in 0 until if (complete || capped) 1 else PAGES_PER_REFRESH) {
            val query = buildString {
                append("?count=$PAGE_SIZE")
                if (cursor.isNotBlank()) append("&cursor=${URLEncoder.encode(cursor, StandardCharsets.UTF_8.name())}")
            }
            val root = try {
                JSONObject(AnalyticsClient.read("https://api.fxtwitter.com/2/profile/$encoded/statuses$query", ""))
            } catch (_: Exception) {
                break
            }
            if (root.optInt("code", 200) >= 400) error(root.optString("message", "FxTwitter banger scan failed"))
            fetchedAnyPage = true
            val results = root.optJSONArray("results") ?: JSONArray()
            for (index in 0 until results.length()) {
                val status = results.optJSONObject(index) ?: continue
                if (!isOwnPost(status, requested)) continue
                val post = AnalyticsClient.parseFxPost(status)
                if (!complete && !capped) postsScanned++
                val score = BangerScore.calculate(post)
                if (winner == null || BangerScore.compare(post, winner) > 0 || post.url == winner?.url) {
                    winner = post
                    winnerScore = score
                }
                if (postsScanned >= MAX_POSTS) break
            }
            if (postsScanned >= MAX_POSTS) capped = true
            if (complete || capped) break
            if (results.length() == 0) {
                complete = true
                cursor = ""
                break
            }
            val next = root.optJSONObject("cursor")?.optString("bottom").orEmpty()
            if (next.isBlank()) {
                complete = true
                cursor = ""
                break
            }
            if (!seenCursors.add(next)) break
            cursor = next
        }
        if (!fetchedAnyPage) error("FxTwitter banger scan unavailable")
        if (postsScanned >= MAX_POSTS) complete = false
        val stored = JSONObject()
            .put("score", winnerScore)
            .put("complete", complete)
            .put("postsScanned", postsScanned)
            .put("capped", capped)
            .put("cursor", cursor)
            .put("updatedAt", System.currentTimeMillis())
        winner?.let { stored.put("post", postToJson(it)) }
        TwidgetStore.withAccountGeneration(context, username, generation) { prefs.edit().putString(key, stored.toString()).apply() }
        return BangerResult(winner, winnerScore, complete, postsScanned, capped)
    }

    internal fun isOwnPost(status: JSONObject, username: String): Boolean {
        if (status.optString("type") != "status") return false
        if (!status.optJSONObject("author")?.optString("screen_name").orEmpty().equals(username, true)) return false
        if (AnalyticsClient.hasValue(status, "reposted_by")) return false
        return status.optString("url").lowercase(Locale.US).contains("/$username/status/") &&
            AnalyticsClient.fxTimestamp(status) in 1..(System.currentTimeMillis() + 5 * 60_000)
    }

    internal fun resultFromJson(json: JSONObject): BangerResult = BangerResult(
        post = json.optJSONObject("post")?.let(::postFromJson),
        score = json.optDouble("score", 0.0),
        complete = json.optBoolean("complete", false),
        postsScanned = json.optInt("postsScanned", 0),
        capped = json.optBoolean("capped", false),
    )

    private fun postFromJson(json: JSONObject): PostSummary =
        requireNotNull(NetworkResponseParsers.parseBridgePost(json))

    private fun postToJson(post: PostSummary): JSONObject = AnalyticsClient.serializePost(post)

}

// Lifetime audience response. These are Twidget product weights, not X's
// per-viewer prediction weights. Must match bridge/src/banger-score.js.
internal object BangerScore {
    fun calculate(post: PostSummary): Double {
        val likes = post.likes.coerceAtLeast(0).toDouble()
        val bonus = post.reposts.coerceAtLeast(0) * 2.0 +
            post.replies.coerceAtLeast(0) * 0.25 + post.quotes.coerceAtLeast(0) * 0.5
        return likes + minOf(likes * 0.25, bonus)
    }

    fun compare(candidate: PostSummary, incumbent: PostSummary): Int {
        val engagement = calculate(candidate).compareTo(calculate(incumbent))
        return if (engagement != 0) engagement else
            candidate.views.coerceAtLeast(0).compareTo(incumbent.views.coerceAtLeast(0))
    }
}
