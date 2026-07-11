package com.tjg.twidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar

/**
 * Opt-in shared history pool. When enabled, each tracked account is registered
 * with the Twidget bridge (which sources the ongoing numbers itself) and the
 * pooled per-day history is merged back into local charts. Ordinary local
 * samples are never uploaded. An explicit X Analytics import sends only the
 * CSV movements, which the bridge independently validates against live and
 * stored follower snapshots before admitting any gap days.
 */
object HistoryPool {
    private const val POOL_PREFS = "twidget_pool"
    private const val RETRY_DELAY_MS = 60 * 60 * 1000L

    fun enrich(context: Context, stats: ProfileStats, settings: TwidgetSettings, endpoint: BridgeEndpoint): ProfileStats {
        if (!settings.shareHistory) return stats
        if (stats.isPrivate == true) return stats
        val username = stats.userName.trim().trimStart('@')
        if (username.isBlank()) return stats
        // Pool state is per-day server-side; one exchange per account per day.
        if (!needsSync(context, username)) return stats
        markAttempted(context, username)
        val pooled = runCatching { fetchPooledHistory(context, username, endpoint) }.getOrNull() ?: return stats
        markSynced(context, username)
        return if (pooled.isEmpty()) stats else stats.copy(history = stats.history + pooled)
    }

    fun importAnalytics(
        context: Context,
        username: String,
        movements: List<XAnalyticsMovement>,
        endpoint: BridgeEndpoint,
    ): BridgeAnalyticsImport {
        val payload = JSONObject().put("movements", JSONArray().apply {
            movements.forEach { movement ->
                put(JSONObject()
                    .put("date", movement.date.toString())
                    .put("newFollows", movement.newFollows)
                    .put("unfollows", movement.unfollows))
            }
        })
        val body = request(
            context,
            "POST",
            "${endpoint.url}/history/${encode(username)}/analytics-import",
            payload.toString(),
            endpoint.token,
        )
        val root = JSONObject(body)
        return BridgeAnalyticsImport(
            accepted = root.optInt("accepted", 0),
            checkedAnchors = root.optInt("checkedAnchors", 0),
            history = parseHistory(root.optJSONArray("history") ?: JSONArray()),
        )
    }

    // Registers the account when unknown and returns the pooled history.
    private fun fetchPooledHistory(context: Context, username: String, endpoint: BridgeEndpoint): List<HistorySample> {
        val body = request(context, "GET", "${endpoint.url}/history/${encode(username)}", null, endpoint.token)
        val array = JSONObject(body).optJSONArray("history") ?: JSONArray()
        return parseHistory(array)
    }

    private fun parseHistory(array: JSONArray): List<HistorySample> =
        List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { sample ->
                sample ?: return@mapNotNull null
                val timestamp = sample.optLong("ts", sample.optLong("timestamp", 0L))
                if (timestamp <= 0L) return@mapNotNull null
                val dayLabel = sample.optString("dayLabel", "")
                HistorySample(
                    dayLabel = dayLabel,
                    followers = sample.optLong("followers", 0L),
                    following = sample.optLong("following", sample.optLong("followings", 0L)),
                    posts = sample.optLong("posts", 0L),
                    likes = sample.optLong("likes", 0L),
                    timestamp = localDayFromServer(timestamp, dayLabel),
                    estimated = sample.optBoolean("est", false),
                    followersKnown = sample.has("followers") && !sample.isNull("followers"),
                    followingKnown = (sample.has("following") && !sample.isNull("following")) ||
                        (sample.has("followings") && !sample.isNull("followings")),
                    postsKnown = sample.has("posts") && !sample.isNull("posts"),
                    likesKnown = sample.has("likes") && !sample.isNull("likes"),
                    imported = sample.optString("src") == "x_analytics",
                    sharedImport = sample.optString("src") == "x_analytics",
                )
            }

    private fun needsSync(context: Context, username: String): Boolean =
        poolPrefs(context).getLong("synced_${username.lowercase()}", 0L) < startOfToday() &&
            poolPrefs(context).getLong("attempted_${username.lowercase()}", 0L) <
                System.currentTimeMillis() - RETRY_DELAY_MS

    private fun markAttempted(context: Context, username: String) {
        poolPrefs(context).edit()
            .putLong("attempted_${username.lowercase()}", System.currentTimeMillis())
            .apply()
    }

    private fun markSynced(context: Context, username: String) {
        poolPrefs(context).edit()
            .putLong("synced_${username.lowercase()}", System.currentTimeMillis())
            .apply()
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun poolPrefs(context: Context) =
        context.getSharedPreferences(POOL_PREFS, Context.MODE_PRIVATE)

    private fun encode(username: String): String =
        URLEncoder.encode(username, StandardCharsets.UTF_8.name())

    private fun request(context: Context, method: String, url: String, body: String?, apiKey: String): String {
        val startedAt = System.currentTimeMillis()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            connection.setRequestProperty("X-Rettiwt-Api-Key", apiKey)
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val code: Int
        val text: String
        try {
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        } catch (error: Exception) {
            BridgeLog.record(context, method, url, null, null, System.currentTimeMillis() - startedAt, requestBody = body, error = error.message)
            throw error
        }
        BridgeLog.record(context, method, url, code, text, System.currentTimeMillis() - startedAt, requestBody = body)
        if (code !in 200..299) throw IllegalStateException("Pool HTTP $code: ${text.take(200)}")
        return text
    }

    private fun localDayFromServer(timestamp: Long, dayLabel: String): Long =
        HistoryDates.localDayFromServer(timestamp, dayLabel)
}

data class BridgeAnalyticsImport(
    val accepted: Int,
    val checkedAnchors: Int,
    val history: List<HistorySample>,
)
