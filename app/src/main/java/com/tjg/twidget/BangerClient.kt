package com.tjg.twidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

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
    private const val VERSION = 1
    private const val PAGES_PER_REFRESH = 5
    private const val MAX_POSTS = 1_000
    private const val PAGE_SIZE = 100

    fun refresh(
        context: Context,
        username: String,
        followers: Long,
        settings: TwidgetSettings,
        endpoint: BridgeEndpoint,
    ): BangerResult = if (settings.shareHistory) {
        fetchShared(context, username, endpoint)
    } else {
        refreshLocal(context, username, followers)
    }

    fun clear(context: Context, username: String) {
        val suffix = ":${username.trim().trimStart('@').lowercase(Locale.US)}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.endsWith(suffix) }.forEach(edit::remove)
        edit.apply()
    }

    private fun fetchShared(context: Context, username: String, endpoint: BridgeEndpoint): BangerResult {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val root = JSONObject(AnalyticsClient.read("${endpoint.url}/banger/$encoded", endpoint.token, context))
        return resultFromJson(root)
    }

    private fun refreshLocal(context: Context, username: String, followers: Long): BangerResult {
        val key = "v$VERSION:${username.lowercase(Locale.US)}"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        var winner = state.optJSONObject("post")?.let(::postFromJson)
        var winnerScore = state.optDouble("score", 0.0)
        var complete = state.optBoolean("complete", false)
        var postsScanned = state.optInt("postsScanned", 0)
        var capped = state.optBoolean("capped", false)
        val baselineFollowers = state.optLong("baselineFollowers", followers.coerceAtLeast(1)).coerceAtLeast(1)
        var cursor = if (complete || capped) "" else state.optString("cursor")
        val seenCursors = mutableSetOf<String>()
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val requested = username.lowercase(Locale.US)

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
            val results = root.optJSONArray("results") ?: JSONArray()
            for (index in 0 until results.length()) {
                val status = results.optJSONObject(index) ?: continue
                if (!isOwnOriginal(status, requested)) continue
                val post = AnalyticsClient.parseFxPost(status)
                if (post.views <= 0) continue
                if (!complete && !capped) postsScanned++
                val score = BangerScore.calculate(post, baselineFollowers)
                if (winner == null || score > winnerScore || post.url == winner?.url) {
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
        if (postsScanned >= MAX_POSTS) complete = false
        val stored = JSONObject()
            .put("score", winnerScore)
            .put("complete", complete)
            .put("postsScanned", postsScanned)
            .put("capped", capped)
            .put("baselineFollowers", baselineFollowers)
            .put("cursor", cursor)
            .put("updatedAt", System.currentTimeMillis())
        winner?.let { stored.put("post", postToJson(it)) }
        prefs.edit().putString(key, stored.toString()).apply()
        return BangerResult(winner, winnerScore, complete, postsScanned, capped)
    }

    private fun isOwnOriginal(status: JSONObject, username: String): Boolean {
        if (status.optString("type") != "status") return false
        if (!status.optJSONObject("author")?.optString("screen_name").orEmpty().equals(username, true)) return false
        if (AnalyticsClient.hasValue(status, "reposted_by") || AnalyticsClient.hasValue(status, "replying_to") ||
            AnalyticsClient.hasValue(status, "in_reply_to_status_id") || AnalyticsClient.hasValue(status, "in_reply_to_status_id_str")) return false
        val id = status.optString("id")
        val conversation = status.optString("conversation_id")
        if (conversation.isNotBlank() && id.isNotBlank() && conversation != id) return false
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

    private fun postFromJson(json: JSONObject) = PostSummary(
        url = json.optString("url"), text = json.optString("text"), views = json.optLong("views"),
        likes = json.optLong("likes"), replies = json.optLong("replies"), reposts = json.optLong("reposts"),
        quotes = json.optLong("quotes"), engagements = json.optLong("engagements"), timestamp = json.optLong("ts"),
        createdAt = json.optString("createdAt"), authorName = json.optString("authorName"),
        authorUserName = json.optString("authorUserName"), authorAvatar = json.optString("authorAvatar"),
        links = json.optJSONArray("links")?.let { array ->
            List(array.length()) { array.optJSONObject(it) }.mapNotNull { item ->
                item?.let { PostLink(it.optString("display"), it.optString("url")) }
            }
        }.orEmpty(),
        media = json.optJSONArray("media")?.let { array ->
            List(array.length()) { array.optJSONObject(it) }.mapNotNull { item ->
                item?.let { PostMedia(it.optString("type"), it.optString("url"), it.optString("alt"), it.optLong("width"), it.optLong("height")) }
            }
        }.orEmpty(),
    )

    private fun postToJson(post: PostSummary) = JSONObject()
        .put("url", post.url).put("text", post.text).put("views", post.views).put("likes", post.likes)
        .put("replies", post.replies).put("reposts", post.reposts).put("quotes", post.quotes)
        .put("engagements", post.engagements).put("ts", post.timestamp).put("createdAt", post.createdAt)
        .put("authorName", post.authorName).put("authorUserName", post.authorUserName).put("authorAvatar", post.authorAvatar)
        .put("links", JSONArray(post.links.map { JSONObject().put("display", it.display).put("url", it.url) }))
        .put("media", JSONArray(post.media.map {
            JSONObject().put("type", it.type).put("url", it.url).put("alt", it.alt).put("width", it.width).put("height", it.height)
        }))
}

internal object BangerScore {
    fun calculate(post: PostSummary, followers: Long): Double {
        if (post.views <= 0) return 0.0
        val views = post.views.toDouble()
        val reachReference = maxOf(1_000.0, followers.coerceAtLeast(1) * 10.0)
        val reach = (ln(views + 1.0) / ln(reachReference + 1.0)).coerceIn(0.05, 2.0)
        val approval = (post.likes + 2.0) / (views + 100.0)
        val meaningful = post.replies * 2.0 + post.reposts * 3.0 + post.quotes * 4.0
        val interaction = (meaningful + 1.0) / (views + 200.0)
        return (reach * (approval / 0.03).coerceAtLeast(0.001) *
            (interaction / 0.01).coerceAtLeast(0.001)).pow(1.0 / 3.0)
    }
}
