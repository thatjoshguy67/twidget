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

/**
 * Fetches post analytics from the bridge's /analytics endpoint and caches the
 * latest result per account. The dashboard shows the cached copy immediately
 * and refreshes in the background when it goes stale.
 */
object AnalyticsClient {
    private const val PREFS = "twidget_analytics"
    private const val CACHE_VERSION = 2
    private const val STALE_MS = 60 * 60 * 1000L // 1 hour

    fun cached(context: Context, username: String): PostAnalytics? {
        val raw = prefs(context).getString(key(username), null) ?: return null
        return runCatching { parse(JSONObject(raw)) }.getOrNull()
    }

    fun isStale(analytics: PostAnalytics?): Boolean =
        analytics == null || System.currentTimeMillis() - analytics.cachedAt > STALE_MS

    fun refresh(context: Context, username: String, bridgeUrl: String): PostAnalytics {
        val clean = username.trim().trimStart('@')
        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
        val body = read("${bridgeUrl.trimEnd('/')}/analytics/$encoded", TwidgetStore.settings(context).apiKey)
        val analytics = parse(JSONObject(body)).copy(cachedAt = System.currentTimeMillis())
        prefs(context).edit().putString(key(clean), serialize(analytics).toString()).apply()
        return analytics
    }

    private fun parse(json: JSONObject): PostAnalytics {
        val reach = json.optJSONObject("reach") ?: JSONObject()
        val engagement = json.optJSONObject("engagement") ?: JSONObject()
        return PostAnalytics(
            userName = json.optString("userName"),
            followers = json.optLong("followers"),
            postsAnalyzed = json.optInt("postsAnalyzed"),
            windowDays = json.optInt("windowDays", 7),
            totalViews = reach.optLong("totalViews"),
            avgViews = reach.optDouble("avgViews", 0.0),
            medianViews = reach.optDouble("medianViews", 0.0),
            avgViewsPerFollower = reach.optDouble("avgViewsPerFollower", 0.0),
            totalEngagements = engagement.optLong("totalEngagements"),
            avgEngagements = engagement.optDouble("avgEngagements", 0.0),
            medianEngagements = engagement.optDouble("medianEngagements", 0.0),
            avgEngagementsPerFollower = engagement.optDouble("avgEngagementsPerFollower", 0.0),
            engagementRate = engagement.optDouble("engagementRate", 0.0),
            best = parsePost(json.optJSONObject("best")),
            worst = parsePost(json.optJSONObject("worst")),
            cachedAt = json.optLong("cachedAt", System.currentTimeMillis()),
        )
    }

    private fun parsePost(json: JSONObject?): PostSummary? {
        json ?: return null
        return PostSummary(
            url = json.optString("url"),
            text = json.optString("text"),
            views = json.optLong("views"),
            likes = json.optLong("likes"),
            replies = json.optLong("replies"),
            reposts = json.optLong("reposts"),
            quotes = json.optLong("quotes"),
            engagements = json.optLong("engagements"),
            timestamp = json.optLong("ts"),
            createdAt = json.optString("createdAt"),
            authorName = json.optString("authorName"),
            authorUserName = json.optString("authorUserName"),
            authorAvatar = json.optString("authorAvatar"),
            links = parseLinks(json.optJSONArray("links")),
            media = parseMedia(json.optJSONArray("media")),
        )
    }

    private fun parseLinks(array: JSONArray?): List<PostLink> {
        array ?: return emptyList()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { item ->
                item ?: return@mapNotNull null
                PostLink(
                    display = item.optString("display"),
                    url = item.optString("url"),
                )
            }
            .filter { it.display.isNotBlank() && it.url.isNotBlank() }
    }

    private fun parseMedia(array: JSONArray?): List<PostMedia> {
        array ?: return emptyList()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { item ->
                item ?: return@mapNotNull null
                PostMedia(
                    type = item.optString("type"),
                    url = item.optString("url"),
                    alt = item.optString("alt"),
                    width = item.optLong("width"),
                    height = item.optLong("height"),
                )
            }
            .filter { it.url.isNotBlank() }
    }

    private fun serialize(a: PostAnalytics): JSONObject = JSONObject()
        .put("userName", a.userName)
        .put("followers", a.followers)
        .put("postsAnalyzed", a.postsAnalyzed)
        .put("windowDays", a.windowDays)
        .put("cachedAt", a.cachedAt)
        .put(
            "reach",
            JSONObject()
                .put("totalViews", a.totalViews)
                .put("avgViews", a.avgViews)
                .put("medianViews", a.medianViews)
                .put("avgViewsPerFollower", a.avgViewsPerFollower),
        )
        .put(
            "engagement",
            JSONObject()
                .put("totalEngagements", a.totalEngagements)
                .put("avgEngagements", a.avgEngagements)
                .put("medianEngagements", a.medianEngagements)
                .put("avgEngagementsPerFollower", a.avgEngagementsPerFollower)
                .put("engagementRate", a.engagementRate),
        )
        .apply {
            a.best?.let { put("best", serializePost(it)) }
            a.worst?.let { put("worst", serializePost(it)) }
        }

    private fun serializePost(p: PostSummary): JSONObject = JSONObject()
        .put("url", p.url)
        .put("text", p.text)
        .put("views", p.views)
        .put("likes", p.likes)
        .put("replies", p.replies)
        .put("reposts", p.reposts)
        .put("quotes", p.quotes)
        .put("engagements", p.engagements)
        .put("ts", p.timestamp)
        .put("createdAt", p.createdAt)
        .put("authorName", p.authorName)
        .put("authorUserName", p.authorUserName)
        .put("authorAvatar", p.authorAvatar)
        .put("links", JSONArray(p.links.map { serializeLink(it) }))
        .put("media", JSONArray(p.media.map { serializeMedia(it) }))

    private fun serializeLink(link: PostLink): JSONObject = JSONObject()
        .put("display", link.display)
        .put("url", link.url)

    private fun serializeMedia(media: PostMedia): JSONObject = JSONObject()
        .put("type", media.type)
        .put("url", media.url)
        .put("alt", media.alt)
        .put("width", media.width)
        .put("height", media.height)

    private fun read(url: String, apiKey: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            connection.setRequestProperty("X-Rettiwt-Api-Key", apiKey)
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("Analytics HTTP $code: ${text.take(200)}")
        return text
    }

    private fun key(username: String): String = "v$CACHE_VERSION:${username.trim().trimStart('@').lowercase()}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
