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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fetches post analytics directly from FxTwitter when that provider is active,
 * or from the configured bridge for bridge-backed sources. Results are cached
 * per provider and account so switching sources cannot show stale mixed data.
 */
object AnalyticsClient {
    private const val PREFS = "twidget_analytics"
    private const val CACHE_VERSION = 4
    private const val STALE_MS = 60 * 60 * 1000L // 1 hour
    private const val ANALYTICS_PAGE_SIZE = 100
    private const val MAX_ANALYTICS_PAGES = 10
    private const val MAX_ANALYTICS_STATUSES = 200
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    fun cached(context: Context, username: String): PostAnalytics? {
        val raw = prefs(context).getString(key(context, username), null) ?: return null
        return runCatching { parse(JSONObject(raw)) }.getOrNull()
    }

    fun isStale(analytics: PostAnalytics?): Boolean =
        analytics == null || System.currentTimeMillis() - analytics.cachedAt > STALE_MS

    fun cacheBanger(context: Context, username: String, result: BangerResult) {
        val existing = cached(context, username) ?: return
        val updated = existing.copy(
            banger = result.post ?: existing.banger,
            bangerComplete = result.complete,
            bangerPostsScanned = result.postsScanned,
        )
        prefs(context).edit().putString(key(context, username), serialize(updated).toString()).apply()
    }

    fun refresh(context: Context, username: String): PostAnalytics {
        val clean = username.trim().trimStart('@')
        val previous = cached(context, clean)
        val settings = TwidgetStore.settings(context)
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        val weekly = if (settings.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER) {
            ProviderFallback.directThenOptionalFallback(
                direct = { fetchFxTwitter(clean) },
                // Sharing history is the bridge opt-in: only then does the
                // bridge remain an optional fallback, and a healthy FxTwitter
                // setup never contacts it either way.
                fallback = if (settings.shareHistory) ({ fetchBridge(context, clean, endpoint) }) else null,
            )
        } else {
            fetchBridge(context, clean, endpoint)
        }
        val hallOfFame = runCatching {
            BangerClient.refresh(context, clean, settings, endpoint)
        }.getOrNull()
        if (hallOfFame != null && !hallOfFame.complete && !hallOfFame.capped) {
            BangerScanWorker.enqueue(context, clean)
        }
        val analytics = weekly.copy(
            banger = hallOfFame?.post ?: previous?.banger,
            bangerComplete = hallOfFame?.complete ?: previous?.bangerComplete ?: false,
            bangerPostsScanned = hallOfFame?.postsScanned ?: previous?.bangerPostsScanned ?: 0,
            cachedAt = System.currentTimeMillis(),
        )
        prefs(context).edit()
            .putString(key(context, clean), serialize(analytics).toString())
            .apply()
        return analytics
    }

    private fun fetchBridge(context: Context, username: String, endpoint: BridgeEndpoint): PostAnalytics {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val body = read("${endpoint.url}/analytics/$encoded", endpoint.token, logContext = context)
        return parse(JSONObject(body))
    }

    private fun fetchFxTwitter(username: String): PostAnalytics {
        val profile = FxTwitterClient.fetchProfile(username)
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val statuses = mutableListOf<JSONObject>()
        val seenIds = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor = ""
        var pages = 0
        var reachedWindowBoundary = false
        while (pages < MAX_ANALYTICS_PAGES && statuses.size < MAX_ANALYTICS_STATUSES) {
            val query = buildString {
                append("?count=$ANALYTICS_PAGE_SIZE")
                if (cursor.isBlank()) append("&since=$weekAgo")
                else append("&cursor=${URLEncoder.encode(cursor, StandardCharsets.UTF_8.name())}")
            }
            val body = read("https://api.fxtwitter.com/2/profile/$encoded/statuses$query", "")
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
                page.map(::fxTimestamp),
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
        val requested = username.lowercase(Locale.US)
        val posts = statuses
            .mapNotNull { status -> status.takeIf { isOwnWeeklyPost(it, requested, weekAgo, now) }?.let(::parseFxPost) }
            .sortedByDescending { it.timestamp }
        val views = posts.map { it.views }
        val engagements = posts.map { it.engagements }
        val totalViews = views.sum()
        val totalEngagements = engagements.sum()
        val best = posts.maxByOrNull { it.engagements }
        val worst = posts.minByOrNull { it.engagements }
        val count = posts.size
        return PostAnalytics(
            userName = username,
            followers = profile.followersCount,
            postsAnalyzed = count,
            statusesInspected = statuses.size,
            isSampled = cursor.isNotBlank() && !reachedWindowBoundary,
            windowDays = 7,
            totalViews = totalViews,
            avgViews = mean(views),
            medianViews = median(views),
            avgViewsPerFollower = if (profile.followersCount > 0 && count > 0) {
                totalViews.toDouble() / count / profile.followersCount
            } else 0.0,
            totalEngagements = totalEngagements,
            avgEngagements = mean(engagements),
            medianEngagements = median(engagements),
            avgEngagementsPerFollower = if (profile.followersCount > 0 && count > 0) {
                totalEngagements.toDouble() / count / profile.followersCount
            } else 0.0,
            engagementRate = if (totalViews > 0) totalEngagements.toDouble() / totalViews else 0.0,
            best = best,
            worst = worst.takeIf { posts.size >= 2 },
            cachedAt = System.currentTimeMillis(),
        )
    }

    private fun isOwnWeeklyPost(status: JSONObject, username: String, weekAgo: Long, now: Long): Boolean =
        FxPostPolicy.isOwnOriginalInWindow(
            FxStatusCandidate(
                type = status.optString("type"),
                authorUsername = status.optJSONObject("author")?.optString("screen_name").orEmpty(),
                url = status.optString("url"),
                timestamp = fxTimestamp(status),
                id = status.optString("id"),
                conversationId = status.optString("conversation_id"),
                isRepost = hasValue(status, "reposted_by"),
                isReply = hasValue(status, "replying_to") ||
                    hasValue(status, "in_reply_to_status_id") ||
                    hasValue(status, "in_reply_to_status_id_str"),
            ),
            username,
            weekAgo,
            now,
        )

    internal fun parseFxPost(status: JSONObject): PostSummary {
        val author = status.optJSONObject("author") ?: JSONObject()
        val likes = status.optLong("likes")
        val replies = status.optLong("replies")
        val reposts = status.optLong("reposts")
        val quotes = status.optLong("quotes")
        return PostSummary(
            url = safeWebUrl(status.optString("url")),
            text = status.optString("text").replace(Regex("\\s+"), " ").trim().take(180),
            views = status.optLong("views"),
            likes = likes,
            replies = replies,
            reposts = reposts,
            quotes = quotes,
            engagements = likes + replies + reposts + quotes,
            timestamp = fxTimestamp(status),
            createdAt = status.optString("created_at"),
            authorName = author.optString("name"),
            authorUserName = author.optString("screen_name"),
            authorAvatar = highResolutionProfileImageUrl(author.optString("avatar_url")),
            links = parseFxLinks(status.optJSONObject("raw_text")?.optJSONArray("facets")),
            media = parseFxMedia(status.optJSONObject("media")?.optJSONArray("all")),
        )
    }

    private fun parseFxLinks(array: JSONArray?): List<PostLink> {
        array ?: return emptyList()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { item ->
                item?.takeIf { it.optString("type") == "url" } ?: return@mapNotNull null
                val url = safeWebUrl(item.optString("replacement").ifBlank { item.optString("original") })
                val display = item.optString("display")
                PostLink(display, url).takeIf { display.isNotBlank() && url.isNotBlank() }
            }
    }

    private fun parseFxMedia(array: JSONArray?): List<PostMedia> {
        array ?: return emptyList()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { item ->
                item ?: return@mapNotNull null
                val url = safeWebUrl(item.optString("thumbnail_url").ifBlank { item.optString("url") })
                PostMedia(
                    type = item.optString("type"),
                    url = url,
                    alt = item.optString("altText"),
                    width = item.optLong("width"),
                    height = item.optLong("height"),
                ).takeIf { url.isNotBlank() }
            }
            .take(4)
    }

    internal fun fxTimestamp(status: JSONObject): Long {
        val numeric = status.optLong("created_timestamp")
        if (numeric > 0) return if (numeric < 1_000_000_000_000L) numeric * 1000 else numeric
        return runCatching {
            SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)
                .parse(status.optString("created_at"))?.time ?: 0L
        }.getOrDefault(0L)
    }

    internal fun hasValue(json: JSONObject, key: String): Boolean =
        json.has(key) && !json.isNull(key) && when (val value = json.opt(key)) {
            is String -> value.isNotBlank()
            else -> value != null
        }

    private fun mean(values: List<Long>): Double =
        if (values.isEmpty()) 0.0 else values.sum().toDouble() / values.size

    private fun median(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle].toDouble()
        else (sorted[middle - 1].toDouble() + sorted[middle]) / 2.0
    }

    private fun parse(json: JSONObject): PostAnalytics {
        val reach = json.optJSONObject("reach") ?: JSONObject()
        val engagement = json.optJSONObject("engagement") ?: JSONObject()
        return PostAnalytics(
            userName = json.optString("userName"),
            followers = json.optLong("followers"),
            postsAnalyzed = json.optInt("postsAnalyzed"),
            statusesInspected = json.optInt("statusesInspected", json.optInt("postsAnalyzed")),
            isSampled = json.optBoolean("isSampled", false),
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
            banger = parsePost(json.optJSONObject("banger")),
            bangerComplete = json.optBoolean("bangerComplete", false),
            bangerPostsScanned = json.optInt("bangerPostsScanned", 0),
            cachedAt = json.optLong("cachedAt", System.currentTimeMillis()),
        )
    }

    private fun parsePost(json: JSONObject?): PostSummary? {
        json ?: return null
        return PostSummary(
            url = safeWebUrl(json.optString("url")),
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
            authorAvatar = safeWebUrl(json.optString("authorAvatar")),
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
                    url = safeWebUrl(item.optString("url")),
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
                    url = safeWebUrl(item.optString("url")),
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
        .put("statusesInspected", a.statusesInspected)
        .put("isSampled", a.isSampled)
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
            a.banger?.let { put("banger", serializePost(it)) }
            put("bangerComplete", a.bangerComplete)
            put("bangerPostsScanned", a.bangerPostsScanned)
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

    // `logContext` is only passed for bridge requests; direct FxTwitter calls
    // stay out of the debug bridge log.
    internal fun read(url: String, apiKey: String, logContext: Context? = null): String {
        val startedAt = System.currentTimeMillis()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Twidget (Android)")
        if (apiKey.isNotBlank()) {
            connection.setRequestProperty("X-Rettiwt-Api-Key", apiKey)
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        val code: Int
        val text: String
        try {
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        } catch (error: Exception) {
            BridgeLog.record(logContext, "GET", url, null, null, System.currentTimeMillis() - startedAt, error = error.message)
            throw error
        }
        BridgeLog.record(logContext, "GET", url, code, text, System.currentTimeMillis() - startedAt)
        if (code !in 200..299) throw IllegalStateException("Analytics HTTP $code: ${text.take(200)}")
        return text
    }

    private fun highResolutionProfileImageUrl(url: String): String =
        url.trim()
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))")) { "${it.groupValues[1]}400x400" }

    private fun safeWebUrl(value: String): String = runCatching {
        URL(value.trim()).takeIf { it.protocol == "https" || it.protocol == "http" }?.toString().orEmpty()
    }.getOrDefault("")

    private fun key(context: Context, username: String): String {
        val source = TwidgetStore.settings(context).dataSource
        return "v$CACHE_VERSION:$source:${username.trim().trimStart('@').lowercase(Locale.US)}"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
