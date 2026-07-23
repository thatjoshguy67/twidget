package com.tjg.twidget.analytics

import android.content.Context
import com.tjg.twidget.core.ActivityPostPolicy
import com.tjg.twidget.core.AnalyticsPaging
import com.tjg.twidget.core.FxStatusCandidate
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.core.ProviderFallback
import com.tjg.twidget.data.BridgeEndpoint
import com.tjg.twidget.data.DailyStreakStore
import com.tjg.twidget.data.StreakSnapshot
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.TwitterApisClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Detects post/reply activity days for streak tracking. */
object ActivityClient {
    private const val PREFS = "twidget_activity_refresh"
    private const val STALE_MS = 60 * 60 * 1000L
    private const val TWITTERAPIS_STALE_MS = 6 * 60 * 60 * 1000L
    private const val ANALYTICS_PAGE_SIZE = 100
    private const val MAX_ANALYTICS_PAGES = 10
    private const val MAX_ANALYTICS_STATUSES = 200
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    fun refresh(context: Context, username: String): StreakSnapshot {
        val clean = username.trim().trimStart('@')
        val days = runCatching { fetchActiveDays(context, clean) }.getOrDefault(emptySet())
        DailyStreakStore.mergeDays(context, clean, days)
        prefs(context).edit().putLong(refreshKey(clean), System.currentTimeMillis()).apply()
        return DailyStreakStore.snapshot(context, clean)
    }

    fun isStale(context: Context, username: String): Boolean {
        val clean = username.trim().trimStart('@').lowercase(Locale.US)
        val staleAfter = if (TwidgetStore.settings(context).dataSource == TwidgetStore.DATA_SOURCE_TWITTERAPIS) {
            TWITTERAPIS_STALE_MS
        } else {
            STALE_MS
        }
        val last = prefs(context).getLong(refreshKey(clean), 0L)
        return last == 0L || System.currentTimeMillis() - last > staleAfter
    }

    fun snapshot(context: Context, username: String): StreakSnapshot =
        DailyStreakStore.snapshot(context, username.trim().trimStart('@'))

    private fun fetchActiveDays(context: Context, username: String): Set<String> {
        val settings = TwidgetStore.settings(context)
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        return when (settings.dataSource) {
            TwidgetStore.DATA_SOURCE_FXTWITTER -> ProviderFallback.directThenOptionalFallback(
                direct = { fetchFxTwitterDays(username) },
                fallback = if (settings.shareHistory) ({ fetchBridgeDays(context, username, endpoint) }) else null,
            )
            TwidgetStore.DATA_SOURCE_TWITTERAPIS -> ProviderFallback.directThenOptionalFallback(
                direct = { fetchTwitterApisDays(context, username) },
                fallback = { fetchBridgeDays(context, username, endpoint) },
            )
            else -> fetchBridgeDays(context, username, endpoint)
        }
    }

    private fun fetchBridgeDays(context: Context, username: String, endpoint: BridgeEndpoint): Set<String> {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val body = read("${endpoint.url}/analytics/$encoded", endpoint.token)
        val analytics = JSONObject(body)
        val days = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        analytics.optJSONObject("best")?.optLong("ts")?.takeIf { it in weekAgo..now }?.let {
            days += DailyStreakStore.localDayKey(it)
        }
        analytics.optJSONObject("worst")?.optLong("ts")?.takeIf { it in weekAgo..now }?.let {
            days += DailyStreakStore.localDayKey(it)
        }
        return days
    }

    private fun fetchTwitterApisDays(context: Context, username: String): Set<String> {
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        val timeline = AnalyticsClient.collectTwitterApisTimeline(weekAgo) { cursor ->
            TwitterApisClient.fetchTimelinePage(context, username, cursor)
        }
        return timeline.tweets.mapNotNull { tweet ->
            parseTwitterApisActivityDay(tweet, username, weekAgo, now)
        }.toSet()
    }

    private fun fetchFxTwitterDays(username: String): Set<String> {
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val statuses = mutableListOf<JSONObject>()
        val seenIds = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor = ""
        var pages = 0
        while (pages < MAX_ANALYTICS_PAGES && statuses.size < MAX_ANALYTICS_STATUSES) {
            val query = buildString {
                append("?count=$ANALYTICS_PAGE_SIZE")
                if (cursor.isBlank()) append("&since=$weekAgo")
                else append("&cursor=${URLEncoder.encode(cursor, StandardCharsets.UTF_8.name())}")
            }
            val body = read("https://api.fxtwitter.com/2/profile/$encoded/statuses$query", "")
            if (body.isBlank()) break
            val root = JSONObject(body)
            if (root.optInt("code", 200) >= 400) {
                throw IllegalStateException(root.optString("message", "FxTwitter activity failed"))
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
            val reachedWindowBoundary = AnalyticsPaging.reachedWindowBoundary(
                page.map(AnalyticsClient::fxTimestamp),
                weekAgo,
            )
            val nextCursor = root.optJSONObject("cursor")?.optString("bottom").orEmpty()
            if (reachedWindowBoundary || nextCursor.isBlank()) break
            if (!seenCursors.add(nextCursor)) break
            cursor = nextCursor
        }
        val requested = username.lowercase(Locale.US)
        return statuses.mapNotNull { status ->
            fxActivityDay(status, requested, weekAgo, now)
        }.toSet()
    }

    internal fun fxActivityDay(
        status: JSONObject,
        username: String,
        weekAgo: Long,
        now: Long,
    ): String? {
        val candidate = FxStatusCandidate(
            type = status.optString("type"),
            authorUsername = status.optJSONObject("author")?.optString("screen_name").orEmpty(),
            url = status.optString("url"),
            timestamp = AnalyticsClient.fxTimestamp(status),
            id = status.optString("id"),
            conversationId = status.optString("conversation_id"),
            isRepost = AnalyticsClient.hasValue(status, "reposted_by"),
            isReply = AnalyticsClient.hasValue(status, "replying_to") ||
                AnalyticsClient.hasValue(status, "in_reply_to_status_id") ||
                AnalyticsClient.hasValue(status, "in_reply_to_status_id_str"),
        )
        return if (ActivityPostPolicy.isOwnPostOrReplyInWindow(candidate, username, weekAgo, now)) {
            DailyStreakStore.localDayKey(candidate.timestamp)
        } else {
            null
        }
    }

    internal fun parseTwitterApisActivityDay(
        tweet: JSONObject,
        requestedUsername: String,
        weekAgo: Long,
        now: Long,
    ): String? {
        val author = tweet.optJSONObject("author") ?: tweet.optJSONObject("user") ?: JSONObject()
        val authorUsername = sequenceOf("userName", "username", "screen_name")
            .map(author::optString)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trimStart('@')
            .orEmpty()
            .ifBlank { requestedUsername.trim().trimStart('@') }
        if (!authorUsername.equals(requestedUsername.trim().trimStart('@'), ignoreCase = true)) return null
        val isRepost = tweetBoolean(tweet, "is_retweet", "isRetweet", "is_repost", "isRepost") ||
            tweet.has("retweeted_tweet") || tweet.has("reposted_tweet")
        if (isRepost) return null
        val timestamp = AnalyticsClient.twitterApisTimestamp(tweet)
        if (timestamp !in weekAgo..(now + 5 * 60_000L)) return null
        return DailyStreakStore.localDayKey(timestamp)
    }

    private fun tweetBoolean(json: JSONObject, vararg names: String): Boolean = names.any { name ->
        json.has(name) && !json.isNull(name) && when (val value = json.opt(name)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> false
        }
    }

    private fun read(url: String, token: String): String {
        val headers = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
        return HttpTransport.get(url, headers).body
    }

    private fun refreshKey(username: String) = "refresh_${username.lowercase(Locale.US)}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
