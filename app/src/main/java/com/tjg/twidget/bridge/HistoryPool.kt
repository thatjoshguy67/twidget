package com.tjg.twidget.bridge

import android.content.Context
import com.tjg.twidget.analytics.XAnalyticsMovement
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.data.BridgeEndpoint
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.schedule.json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

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
        return NetworkResponseParsers.parseBridgeAnalyticsImport(root)
    }

    // Registers the account when unknown and returns the pooled history.
    private fun fetchPooledHistory(context: Context, username: String, endpoint: BridgeEndpoint): List<HistorySample> {
        val body = request(context, "GET", "${endpoint.url}/history/${encode(username)}", null, endpoint.token)
        val array = JSONObject(body).optJSONArray("history") ?: JSONArray()
        return parseHistory(array)
    }

    private fun parseHistory(array: JSONArray): List<HistorySample> =
        NetworkResponseParsers.parseBridgeHistoryArray(array)

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
        val headers = buildMap {
            if (apiKey.isNotBlank()) {
                put("X-Rettiwt-Api-Key", apiKey)
                put("Authorization", "Bearer $apiKey")
            }
            if (body != null) put("Content-Type", "application/json")
        }
        try {
            val response = if (method == "GET") {
                HttpTransport.get(url, headers)
            } else {
                HttpTransport.post(url, body.orEmpty(), headers)
            }
            BridgeLog.record(context, method, url, response.code, response.body, System.currentTimeMillis() - startedAt, requestBody = body)
            if (response.code !in 200..299) {
                val error = runCatching { JSONObject(response.body) }.getOrNull()
                val detail = error?.optJSONObject("detail")
                throw BridgeImportException(
                    status = response.code,
                    code = error?.optString("error").orEmpty().ifBlank { "pool_http_error" },
                    expectedFollowers = detail?.optLong("expected")?.takeIf { detail.has("expected") },
                    detectedFollowers = detail?.optLong("reconstructed")?.takeIf { detail.has("reconstructed") },
                    message = "Pool HTTP ${response.code}: ${response.body.take(200)}",
                )
            }
            return response.body
        } catch (error: Exception) {
            BridgeLog.record(context, method, url, null, null, System.currentTimeMillis() - startedAt, requestBody = body, error = error.message)
            throw error
        }
    }
}

data class BridgeAnalyticsImport(
    val accepted: Int,
    val checkedAnchors: Int,
    val history: List<HistorySample>,
)

class BridgeImportException(
    val status: Int,
    val code: String,
    val expectedFollowers: Long?,
    val detectedFollowers: Long?,
    message: String,
) : IllegalStateException(message)
