package com.tjg.twidget.analytics

import android.content.Context
import com.tjg.twidget.core.AnalyticsPaging
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.core.SharedRequestCache
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.providers.TwitterApisClient
import com.tjg.twidget.providers.FxTwitterClient
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.security.MessageDigest

/** Read-only weekly provider data shared by analytics and streak detection. No credentials are cached. */
internal object WeeklyTimelineRepository {
    private const val ANALYTICS_PAGE_SIZE = 100
    private const val MAX_ANALYTICS_PAGES = 10
    private const val MAX_ANALYTICS_STATUSES = 200
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L
    private val cache = SharedRequestCache<Timeline>(60_000L)
    data class Timeline(val statuses: List<JSONObject>, val windowStartAt: Long, val checkedAt: Long, val incomplete: Boolean)

    fun invalidateCompleted(username: String) {
        val suffix = ":${username.trim().trimStart('@').lowercase(Locale.US)}"
        cache.invalidateCompleted { it.endsWith(suffix) }
    }

    fun fxTwitter(username: String, userId: String = ""): Timeline = cache.get("fx:${FxTwitterClient.identity(username, userId)}:${username.lowercase(Locale.US)}") {
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        val statuses = mutableListOf<JSONObject>()
        val seenIds = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor = ""
        var pages = 0
        var reachedWindowBoundary = false
        while (pages < MAX_ANALYTICS_PAGES && statuses.size < MAX_ANALYTICS_STATUSES) {
            // The provider's `since` filter can return 404. Bound and filter
            // the retrieved pages locally, just like the bridge.
            val url = FxTwitterClient.timelineUrl(username, userId, ANALYTICS_PAGE_SIZE, cursor, withReplies = true)
            val body = try {
                HttpTransport.requireSuccess(HttpTransport.get(url), "FxTwitter timeline")
            } catch (error: HttpTransport.HttpException) {
                // A reply timeline may advertise a continuation cursor which the
                // upstream rejects. Keep the observed first page as sampled data.
                if (error.code == 404 && cursor.isNotBlank() && statuses.isNotEmpty()) break
                throw error
            }
            if (body.isBlank()) {
                reachedWindowBoundary = true
                break
            }
            val root = JSONObject(body)
            if (root.optInt("code", 200) >= 400) {
                throw IllegalStateException(root.optString("message", "FxTwitter analytics failed"))
            }
            pages++
            val results = root.optJSONArray("results") ?: JSONArray()
            val page = List(results.length()) { index -> results.optJSONObject(index) }.filterNotNull()
            page.forEach { status ->
                val id = status.optString("id")
                if (statuses.size < MAX_ANALYTICS_STATUSES && (id.isBlank() || seenIds.add(id))) {
                    statuses += status
                }
            }
            // The first row may be an old pinned post, so use the final dated
            // row as the page boundary instead of stopping on any old item.
            reachedWindowBoundary = AnalyticsPaging.reachedWindowBoundary(
                page.map(AnalyticsClient::fxTimestamp),
                weekAgo,
            )
            val nextCursor = root.optJSONObject("cursor")?.optString("bottom").orEmpty()
            if (reachedWindowBoundary || nextCursor.isBlank()) {
                cursor = ""
                break
            }
            if (!seenCursors.add(nextCursor)) {
                // A repeated cursor is an incomplete/cyclic upstream walk,
                // so leave it non-empty and surface the result as sampled.
                cursor = nextCursor
                break
            }
            cursor = nextCursor
        }
        Timeline(statuses, weekAgo, now, cursor.isNotBlank() && !reachedWindowBoundary)
    }

    fun twitterApis(context: Context, username: String): Timeline {
        val credential = SecureCredentialStore.read(context, SecureCredentialStore.TWITTERAPIS_API_KEY)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(credential.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return cache.get("twitterapis:$fingerprint:${username.lowercase(Locale.US)}") {
            val now = System.currentTimeMillis()
            val start = now - WEEK_MS
            val result = AnalyticsClient.collectTwitterApisTimeline(start) { TwitterApisClient.fetchTimelinePage(context, username, it) }
            Timeline(result.tweets, start, now, result.incomplete)
        }
    }
}
