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
 * pooled per-day history is merged back into local charts. The app never
 * uploads local samples: the public server remains the authority for pooled
 * history and cannot be poisoned by device data.
 */
object HistoryPool {
    private const val POOL_PREFS = "twidget_pool"
    private const val RETRY_DELAY_MS = 60 * 60 * 1000L

    fun enrich(context: Context, stats: ProfileStats, settings: TwidgetSettings, bridgeUrl: String): ProfileStats {
        if (!settings.shareHistory || bridgeUrl.isBlank()) return stats
        if (stats.isPrivate == true) return stats
        val username = stats.userName.trim().trimStart('@')
        if (username.isBlank()) return stats
        // Pool state is per-day server-side; one exchange per account per day.
        if (!needsSync(context, username)) return stats
        markAttempted(context, username)
        val pooled = runCatching { fetchPooledHistory(username, bridgeUrl, settings.apiKey) }.getOrNull() ?: return stats
        markSynced(context, username)
        return if (pooled.isEmpty()) stats else stats.copy(history = stats.history + pooled)
    }

    // Registers the account when unknown and returns the pooled history.
    private fun fetchPooledHistory(username: String, bridgeUrl: String, apiKey: String): List<HistorySample> {
        val body = request("GET", "$bridgeUrl/history/${encode(username)}", null, apiKey)
        val array = JSONObject(body).optJSONArray("history") ?: JSONArray()
        return List(array.length()) { index -> array.optJSONObject(index) }
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
                )
            }
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

    private fun request(method: String, url: String, body: String?, apiKey: String): String {
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
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Pool HTTP $code: ${text.take(200)}")
        return text
    }

    private fun localDayFromServer(timestamp: Long, dayLabel: String): Long =
        HistoryDates.localDayFromServer(timestamp, dayLabel)
}
