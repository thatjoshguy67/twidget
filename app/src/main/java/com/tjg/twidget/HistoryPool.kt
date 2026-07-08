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
 * pooled per-day history is merged back into local charts. Local samples the
 * server never observed are uploaded once as backfill; the server only accepts
 * them for gap days, so this never overrides pooled data.
 */
object HistoryPool {
    private const val POOL_PREFS = "twidget_pool"

    fun enrich(context: Context, stats: ProfileStats, settings: TwidgetSettings, bridgeUrl: String): ProfileStats {
        if (!settings.shareHistory || bridgeUrl.isBlank()) return stats
        val username = stats.userName.trim().trimStart('@')
        if (username.isBlank()) return stats
        // Pool state is per-day server-side; one exchange per account per day.
        if (!needsSync(context, username)) return stats
        val pooled = runCatching { fetchPooledHistory(username, bridgeUrl) }.getOrNull() ?: return stats
        runCatching { backfillOnce(context, username, bridgeUrl) }
        markSynced(context, username)
        return if (pooled.isEmpty()) stats else stats.copy(history = stats.history + pooled)
    }

    // Registers the account when unknown and returns the pooled history.
    private fun fetchPooledHistory(username: String, bridgeUrl: String): List<HistorySample> {
        val body = request("GET", "$bridgeUrl/history/${encode(username)}", null)
        val array = JSONObject(body).optJSONArray("history") ?: JSONArray()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { sample ->
                sample ?: return@mapNotNull null
                val timestamp = sample.optLong("ts", sample.optLong("timestamp", 0L))
                if (timestamp <= 0L) return@mapNotNull null
                HistorySample(
                    dayLabel = sample.optString("dayLabel", ""),
                    followers = sample.optLong("followers", 0L),
                    following = sample.optLong("following", 0L),
                    posts = sample.optLong("posts", 0L),
                    likes = sample.optLong("likes", 0L),
                    timestamp = timestamp,
                    estimated = sample.optBoolean("est", false),
                )
            }
    }

    // Uploads locally accumulated real samples one time per account; the
    // server keeps only days it never observed. Failures retry next sync
    // because the flag is only set on success.
    private fun backfillOnce(context: Context, username: String, bridgeUrl: String) {
        val prefs = poolPrefs(context)
        val flagKey = "backfilled_${username.lowercase()}"
        if (prefs.getBoolean(flagKey, false)) return
        val samples = TwidgetStore.history(context, username).filterNot { it.estimated }
        if (samples.isEmpty()) {
            prefs.edit().putBoolean(flagKey, true).apply()
            return
        }
        val payload = JSONObject().put(
            "samples",
            JSONArray(samples.map { sample ->
                JSONObject()
                    .put("ts", sample.timestamp)
                    .put("dayLabel", sample.dayLabel)
                    .put("followers", sample.followers)
                    .put("following", sample.following)
                    .put("posts", sample.posts)
                    .put("likes", sample.likes)
            }),
        )
        request("POST", "$bridgeUrl/history/${encode(username)}/backfill", payload.toString())
        prefs.edit().putBoolean(flagKey, true).apply()
    }

    private fun needsSync(context: Context, username: String): Boolean =
        poolPrefs(context).getLong("synced_${username.lowercase()}", 0L) < startOfToday()

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

    private fun request(method: String, url: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
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
}
