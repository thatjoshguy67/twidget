package com.tjg.twidget.analytics

import android.content.Context
import com.tjg.twidget.banger.BangerClient
import com.tjg.twidget.banger.BangerResult
import com.tjg.twidget.banger.BangerScanWorker
import com.tjg.twidget.bridge.BridgeLog
import com.tjg.twidget.core.AnalyticsPaging
import com.tjg.twidget.core.FxPostPolicy
import com.tjg.twidget.core.FxStatusCandidate
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.core.ProviderFallback
import com.tjg.twidget.data.BridgeEndpoint
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.FxTwitterClient
import com.tjg.twidget.providers.TwitterApisClient
import com.tjg.twidget.providers.TwitterApisTimelinePage
import com.tjg.twidget.schedule.json
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class TwitterApisTimelineResult(
    val tweets: List<JSONObject>,
    val incomplete: Boolean,
)

/**
 * Fetches post analytics directly from FxTwitter or TwitterAPIs when either
 * provider is active, or from the configured bridge for bridge-backed sources.
 * Results are cached per provider and account so switching sources cannot show
 * stale mixed data.
 */
object AnalyticsClient {
    private const val PREFS = "twidget_analytics"
    private const val CACHE_VERSION = 4
    private const val STALE_MS = 60 * 60 * 1000L // 1 hour
    private const val TWITTERAPIS_STALE_MS = 6 * 60 * 60 * 1000L
    private const val ANALYTICS_PAGE_SIZE = 100
    private const val MAX_ANALYTICS_PAGES = 10
    private const val MAX_ANALYTICS_STATUSES = 200
    private const val MAX_TWITTERAPIS_PAGES = 5
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    fun cached(context: Context, username: String): PostAnalytics? {
        val raw = prefs(context).getString(key(context, username), null) ?: return null
        val parsed = runCatching { NetworkResponseParsers.parseBridgeAnalytics(JSONObject(raw)) }.getOrNull() ?: return null
        val latest = BangerClient.cached(context, username) ?: return parsed
        return parsed.copy(
            banger = latest.post ?: parsed.banger,
            bangerComplete = latest.complete,
            bangerPostsScanned = latest.postsScanned,
        )
    }

    fun isStale(context: Context, analytics: PostAnalytics?): Boolean {
        val staleAfter = if (TwidgetStore.settings(context).dataSource == TwidgetStore.DATA_SOURCE_TWITTERAPIS) {
            TWITTERAPIS_STALE_MS
        } else {
            STALE_MS
        }
        return analytics == null || System.currentTimeMillis() - analytics.cachedAt > staleAfter
    }

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
        val weekly = when (settings.dataSource) {
            TwidgetStore.DATA_SOURCE_FXTWITTER -> ProviderFallback.directThenOptionalFallback(
                direct = { fetchFxTwitter(clean) },
                // Sharing history is the bridge opt-in: only then does the
                // bridge remain an optional fallback, and a healthy FxTwitter
                // setup never contacts it either way.
                fallback = if (settings.shareHistory) ({ fetchBridge(context, clean, endpoint) }) else null,
            )
            TwidgetStore.DATA_SOURCE_TWITTERAPIS -> ProviderFallback.directThenOptionalFallback(
                direct = { fetchTwitterApis(context, clean) },
                fallback = { fetchBridge(context, clean, endpoint) },
            )
            else -> fetchBridge(context, clean, endpoint)
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
        return NetworkResponseParsers.parseBridgeAnalytics(JSONObject(body))
    }

    private fun fetchTwitterApis(context: Context, username: String): PostAnalytics {
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        val timeline = collectTwitterApisTimeline(weekAgo) { cursor ->
            TwitterApisClient.fetchTimelinePage(context, username, cursor)
        }
        val statuses = timeline.tweets

        val posts = statuses
            .mapNotNull { parseTwitterApisPost(it, username, weekAgo, now) }
            .sortedByDescending { it.timestamp }
        val cachedProfile = TwidgetStore.currentStats(context, username)
        val followers = if (cachedProfile.followersKnown) {
            cachedProfile.followersCount
        } else {
            TwitterApisClient.fetchProfile(context, username).followersCount
        }
        return analyticsFromPosts(
            username = username,
            followers = followers,
            statusesInspected = statuses.size,
            sampled = timeline.incomplete,
            posts = posts,
            cachedAt = now,
        )
    }

    internal fun collectTwitterApisTimeline(
        weekAgo: Long,
        fetchPage: (String) -> TwitterApisTimelinePage,
    ): TwitterApisTimelineResult {
        val statuses = mutableListOf<JSONObject>()
        val seenIds = mutableSetOf<String>()
        val seenCursors = mutableSetOf<String>()
        var cursor = ""
        var pages = 0
        var reachedWindowBoundary = false
        var finishedCoverage = false
        var incomplete = false
        while (pages < MAX_TWITTERAPIS_PAGES && statuses.size < MAX_ANALYTICS_STATUSES) {
            val page = fetchPage(cursor)
            pages++
            page.tweets.forEach { tweet ->
                val id = tweetString(tweet, "id", "tweet_id", "rest_id")
                if (statuses.size < MAX_ANALYTICS_STATUSES && (id.isBlank() || seenIds.add(id))) {
                    statuses += tweet
                }
            }
            reachedWindowBoundary = AnalyticsPaging.reachedWindowBoundary(
                page.tweets.map(::twitterApisTimestamp),
                weekAgo,
            )
            if (page.tweets.isEmpty() || reachedWindowBoundary || !page.hasMore) {
                finishedCoverage = true
                break
            }
            if (page.nextCursor.isBlank() || !seenCursors.add(page.nextCursor)) {
                incomplete = true
                break
            }
            cursor = page.nextCursor
        }
        if (!finishedCoverage && pages >= MAX_TWITTERAPIS_PAGES) incomplete = true
        if (!finishedCoverage && statuses.size >= MAX_ANALYTICS_STATUSES) incomplete = true
        return TwitterApisTimelineResult(statuses, incomplete)
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
        return analyticsFromPosts(
            username = username,
            followers = profile.followersCount,
            statusesInspected = statuses.size,
            sampled = cursor.isNotBlank() && !reachedWindowBoundary,
            posts = posts,
            cachedAt = System.currentTimeMillis(),
        )
    }

    internal fun parseTwitterApisPost(
        tweet: JSONObject,
        requestedUsername: String,
        weekAgo: Long,
        now: Long,
    ): PostSummary? {
        val author = tweet.optJSONObject("author") ?: tweet.optJSONObject("user") ?: JSONObject()
        val authorUsername = tweetString(author, "userName", "username", "screen_name")
            .ifBlank { requestedUsername.trim().trimStart('@') }
        if (!authorUsername.equals(requestedUsername.trim().trimStart('@'), ignoreCase = true)) return null
        val id = tweetString(tweet, "id", "tweet_id", "rest_id")
        val conversationId = tweetString(tweet, "conversation_id", "conversationId")
        val isReply = tweetBoolean(tweet, "is_reply", "isReply") ||
            tweetString(tweet, "in_reply_to_status_id", "inReplyToId", "in_reply_to_tweet_id").isNotBlank()
        val isRepost = tweetBoolean(tweet, "is_retweet", "isRetweet", "is_repost", "isRepost") ||
            tweet.has("retweeted_tweet") || tweet.has("reposted_tweet")
        val timestamp = twitterApisTimestamp(tweet)
        if (isReply || isRepost || timestamp !in weekAgo..(now + 5 * 60_000L)) return null
        if (conversationId.isNotBlank() && id.isNotBlank() && conversationId != id) return null

        val url = safeWebUrl(tweetString(tweet, "url", "tweet_url", "twitterUrl")).ifBlank {
            if (id.isBlank()) "" else "https://x.com/$authorUsername/status/$id"
        }
        val likes = tweetLong(tweet, "favorite_count", "favourite_count", "like_count", "likeCount", "likes")
        val replies = tweetLong(tweet, "reply_count", "replyCount", "replies")
        val reposts = tweetLong(tweet, "retweet_count", "repost_count", "retweetCount", "repostCount", "reposts")
        val quotes = tweetLong(tweet, "quote_count", "quoteCount", "quotes")
        return PostSummary(
            url = url,
            text = tweetString(tweet, "text", "full_text").replace(Regex("\\s+"), " ").trim().take(180),
            views = tweetLong(tweet, "view_count", "viewCount", "views"),
            likes = likes,
            replies = replies,
            reposts = reposts,
            quotes = quotes,
            engagements = likes + replies + reposts + quotes,
            timestamp = timestamp,
            createdAt = tweetString(tweet, "created_at", "createdAt"),
            authorName = tweetString(author, "name"),
            authorUserName = authorUsername,
            authorAvatar = highResolutionProfileImageUrl(
                tweetString(author, "profilePicture", "profile_image_url", "profile_image_url_https", "avatar_url"),
            ),
            links = parseTwitterApisLinks(tweet.optJSONObject("entities")?.optJSONArray("urls")),
            media = parseTwitterApisMedia(tweet),
        )
    }

    private fun analyticsFromPosts(
        username: String,
        followers: Long,
        statusesInspected: Int,
        sampled: Boolean,
        posts: List<PostSummary>,
        cachedAt: Long,
    ): PostAnalytics {
        val views = posts.map { it.views }
        val engagements = posts.map { it.engagements }
        val totalViews = views.sum()
        val totalEngagements = engagements.sum()
        val count = posts.size
        return PostAnalytics(
            userName = username,
            followers = followers,
            postsAnalyzed = count,
            statusesInspected = statusesInspected,
            isSampled = sampled,
            windowDays = 7,
            totalViews = totalViews,
            avgViews = mean(views),
            medianViews = median(views),
            avgViewsPerFollower = if (followers > 0 && count > 0) totalViews.toDouble() / count / followers else 0.0,
            totalEngagements = totalEngagements,
            avgEngagements = mean(engagements),
            medianEngagements = median(engagements),
            avgEngagementsPerFollower = if (followers > 0 && count > 0) {
                totalEngagements.toDouble() / count / followers
            } else 0.0,
            engagementRate = if (totalViews > 0) totalEngagements.toDouble() / totalViews else 0.0,
            best = posts.maxByOrNull { it.engagements },
            worst = posts.minByOrNull { it.engagements }.takeIf { posts.size >= 2 },
            cachedAt = cachedAt,
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

    private fun parseTwitterApisLinks(array: JSONArray?): List<PostLink> {
        array ?: return emptyList()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .mapNotNull { item ->
                item ?: return@mapNotNull null
                val url = safeWebUrl(tweetString(item, "expanded_url", "expandedUrl", "url"))
                val display = tweetString(item, "display_url", "displayUrl").ifBlank { url }
                PostLink(display, url).takeIf { url.isNotBlank() }
            }
    }

    private fun parseTwitterApisMedia(tweet: JSONObject): List<PostMedia> {
        val media = tweet.optJSONArray("media")
            ?: tweet.optJSONObject("media")?.optJSONArray("all")
            ?: tweet.optJSONObject("entities")?.optJSONArray("media")
            ?: return emptyList()
        return List(media.length()) { index -> media.optJSONObject(index) }
            .mapNotNull { item ->
                item ?: return@mapNotNull null
                val url = safeWebUrl(
                    tweetString(item, "preview_image_url", "media_url_https", "media_url", "thumbnail_url", "url"),
                )
                PostMedia(
                    type = tweetString(item, "type"),
                    url = url,
                    alt = tweetString(item, "alt_text", "altText"),
                    width = tweetLong(item, "width"),
                    height = tweetLong(item, "height"),
                ).takeIf { url.isNotBlank() }
            }
            .take(4)
    }

    internal fun twitterApisTimestamp(tweet: JSONObject): Long {
        val numeric = tweetLong(tweet, "created_timestamp", "createdTimestamp", "timestamp")
        if (numeric > 0) return if (numeric < 1_000_000_000_000L) numeric * 1000 else numeric
        val value = tweetString(tweet, "created_at", "createdAt")
        val patterns = listOf("EEE MMM dd HH:mm:ss Z yyyy", "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(value)?.time }.getOrNull()
        } ?: 0L
    }

    private fun tweetString(json: JSONObject, vararg names: String): String = names.firstNotNullOfOrNull { name ->
        json.optString(name).takeIf { it.isNotBlank() && it != "null" }
    }.orEmpty()

    private fun tweetLong(json: JSONObject, vararg names: String): Long = names.firstNotNullOfOrNull { name ->
        if (!json.has(name) || json.isNull(name)) return@firstNotNullOfOrNull null
        when (val value = json.opt(name)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }?.coerceAtLeast(0L) ?: 0L

    private fun tweetBoolean(json: JSONObject, vararg names: String): Boolean = names.any { name ->
        json.has(name) && !json.isNull(name) && when (val value = json.opt(name)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> false
        }
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
        val headers = buildMap {
            put("User-Agent", "Twidget (Android)")
            if (apiKey.isNotBlank()) {
                put("X-Rettiwt-Api-Key", apiKey)
                put("Authorization", "Bearer $apiKey")
            }
        }
        try {
            val response = HttpTransport.get(url, headers, connectTimeoutMs = 12_000, readTimeoutMs = 20_000)
            BridgeLog.record(logContext, "GET", url, response.code, response.body, System.currentTimeMillis() - startedAt)
            return HttpTransport.requireSuccess(response, "Analytics")
        } catch (error: Exception) {
            BridgeLog.record(logContext, "GET", url, null, null, System.currentTimeMillis() - startedAt, error = error.message)
            throw error
        }
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
