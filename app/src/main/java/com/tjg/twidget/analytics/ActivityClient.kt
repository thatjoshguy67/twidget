package com.tjg.twidget.analytics

import android.content.Context
import com.tjg.twidget.core.ActivityPostPolicy
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
import org.json.JSONObject

/** Detects original-tweet activity days for streak tracking. */
object ActivityClient {
    private const val PREFS = "twidget_activity_refresh"
    private const val STALE_MS = 60 * 60 * 1000L
    private const val TWITTERAPIS_STALE_MS = 6 * 60 * 60 * 1000L
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    fun refresh(context: Context, username: String, reportFailure: Boolean = false): StreakSnapshot {
        val clean = TwidgetStore.canonicalAccount(context, username)
        val generation = TwidgetStore.accountGeneration(context, clean)
        val now = System.currentTimeMillis()
        val activity = runCatching { fetchActivity(context, clean, now) }.getOrElse {
            if (reportFailure) throw it
            OriginalActivity(emptySet(), now - WEEK_MS, now, complete = false)
        }
        return TwidgetStore.withAccountGeneration(context, clean, generation) {
            DailyStreakStore.mergeOriginalActivity(
                context = context,
                username = clean,
                timestamps = activity.timestamps,
                windowStartAt = activity.windowStartAt,
                checkedAt = activity.checkedAt,
                complete = activity.complete,
            )
            val key = refreshKey(context, clean)
            prefs(context).edit().putLong("attempt_$key", now).apply {
                if (activity.complete) putLong(key, now)
            }.apply()
            DailyStreakStore.snapshot(context, clean)
        }
    }

    fun isStale(context: Context, username: String): Boolean {
        val clean = username.trim().trimStart('@').lowercase(Locale.US)
        val staleAfter = if (TwidgetStore.settings(context).dataSource == TwidgetStore.DATA_SOURCE_TWITTERAPIS) {
            TWITTERAPIS_STALE_MS
        } else {
            STALE_MS
        }
        val key = refreshKey(context, clean)
        val store = prefs(context)
        val now = System.currentTimeMillis()
        if (now - store.getLong("attempt_$key", 0L) < 60_000L) return false
        val last = store.getLong(key, 0L)
        return last == 0L || now - last > staleAfter
    }

    fun snapshot(context: Context, username: String): StreakSnapshot =
        DailyStreakStore.snapshot(context, username.trim().trimStart('@'))

    private data class OriginalActivity(
        val timestamps: Set<Long>,
        val windowStartAt: Long,
        val checkedAt: Long,
        val complete: Boolean,
    )

    private fun fetchActivity(context: Context, username: String, now: Long): OriginalActivity {
        val settings = TwidgetStore.settings(context)
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        return when (settings.dataSource) {
            TwidgetStore.DATA_SOURCE_FXTWITTER -> ProviderFallback.directThenOptionalFallback(
                direct = { fetchFxTwitterActivity(context, username, now) },
                fallback = if (settings.shareHistory) ({ fetchBridgeActivity(context, username, endpoint, now) }) else null,
            )
            TwidgetStore.DATA_SOURCE_TWITTERAPIS -> ProviderFallback.directThenOptionalFallback(
                direct = { fetchTwitterApisActivity(context, username, now) },
                fallback = { fetchBridgeActivity(context, username, endpoint, now) },
            )
            else -> fetchBridgeActivity(context, username, endpoint, now)
        }
    }

    private fun fetchBridgeActivity(
        context: Context,
        username: String,
        endpoint: BridgeEndpoint,
        now: Long,
    ): OriginalActivity {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val body = read("${endpoint.url}/analytics/$encoded", endpoint.token)
        val analytics = JSONObject(body)
        val weekAgo = now - WEEK_MS
        val timestamps = buildSet {
            val activity = analytics.optJSONArray("activityTimestamps") ?:
                analytics.optJSONObject("dashboardStats")?.optJSONArray("originalPostTimestamps")
            if (activity != null) {
                for (index in 0 until activity.length()) {
                    activity.optLong(index).takeIf { it in weekAgo..now }?.let(::add)
                }
            } else {
                analytics.optJSONObject("best")?.optLong("ts")?.takeIf { it in weekAgo..now }?.let(::add)
                analytics.optJSONObject("worst")?.optLong("ts")?.takeIf { it in weekAgo..now }?.let(::add)
            }
        }
        return OriginalActivity(
            timestamps,
            weekAgo,
            analytics.optLong("cachedAt", now),
            complete = if (analytics.has("activityTimestamps")) analytics.optBoolean("activityComplete", false)
                else analytics.optJSONObject("dashboardStats")?.optBoolean("complete", false) == true,
        )
    }

    private fun fetchTwitterApisActivity(context: Context, username: String, now: Long): OriginalActivity {
        val timeline = WeeklyTimelineRepository.twitterApis(context, username)
        val timestamps = timeline.statuses.mapNotNull { tweet ->
            AnalyticsClient.parseTwitterApisPost(tweet, username, timeline.windowStartAt, timeline.checkedAt)?.timestamp
        }.toSet()
        return OriginalActivity(timestamps, timeline.windowStartAt, timeline.checkedAt, complete = !timeline.incomplete)
    }

    private fun fetchFxTwitterActivity(context: Context, username: String, now: Long): OriginalActivity {
        val timeline = WeeklyTimelineRepository.fxTwitter(username, TwidgetStore.currentStats(context, username).userId)
        val requested = username.lowercase(Locale.US)
        val timestamps = timeline.statuses.mapNotNull {
            fxActivityTimestamp(it, requested, timeline.windowStartAt, timeline.checkedAt)
        }.toSet()
        return OriginalActivity(timestamps, timeline.windowStartAt, timeline.checkedAt, complete = !timeline.incomplete)
    }

    internal fun fxActivityTimestamp(
        status: JSONObject,
        username: String,
        weekAgo: Long,
        now: Long,
    ): Long? {
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
        return if (ActivityPostPolicy.isOwnOriginalInWindow(candidate, username, weekAgo, now)) {
            candidate.timestamp
        } else {
            null
        }
    }

    internal fun parseTwitterApisActivityTimestamp(
        tweet: JSONObject,
        requestedUsername: String,
        weekAgo: Long,
        now: Long,
    ): Long? = AnalyticsClient.parseTwitterApisPost(tweet, requestedUsername, weekAgo, now)?.timestamp

    private fun read(url: String, token: String): String {
        val headers = if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
        return HttpTransport.get(url, headers).body
    }

    private fun refreshKey(context: Context, username: String) =
        "refresh_${TwidgetStore.settings(context).dataSource}_${username.lowercase(Locale.US)}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
