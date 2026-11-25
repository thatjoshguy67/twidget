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
import com.tjg.twidget.core.json
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
    private const val WORST_POST_MATURITY_MS = 24 * 60 * 60 * 1000L
    private const val PREFS = "twidget_analytics"
    private const val CACHE_VERSION = 7
    private const val STALE_MS = 60 * 60 * 1000L // 1 hour
    private const val TWITTERAPIS_STALE_MS = 6 * 60 * 60 * 1000L
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
        if (TwidgetStore.settings(context).shareHistory && analytics?.historicalCheckedAt == 0L) return true
        val staleAfter = if (TwidgetStore.settings(context).dataSource == TwidgetStore.DATA_SOURCE_TWITTERAPIS) {
            TWITTERAPIS_STALE_MS
        } else {
            STALE_MS
        }
        return analytics == null || System.currentTimeMillis() - analytics.cachedAt > staleAfter
    }

    fun mayAttempt(context: Context, username: String): Boolean =
        System.currentTimeMillis() - prefs(context).getLong("attempt:${key(context, username)}", 0L) >= 60_000L

    fun cacheBanger(context: Context, username: String, result: BangerResult) {
        synchronized(com.tjg.twidget.data.TwidgetStore) {
            val existing = cached(context, username) ?: return
            val updated = existing.copy(
                banger = result.post ?: existing.banger,
                bangerComplete = result.complete,
                bangerPostsScanned = result.postsScanned,
            )
            writeWithObservations(context, username, updated)
        }
    }

    fun refresh(context: Context, username: String): PostAnalytics {
        val clean = TwidgetStore.canonicalAccount(context, username)
        val generation = TwidgetStore.accountGeneration(context, clean)
        prefs(context).edit().putLong("attempt:${key(context, clean)}", System.currentTimeMillis()).apply()
        val previous = cached(context, clean)
        val settings = TwidgetStore.settings(context)
        val endpoint = TwidgetStore.bridgeEndpoint(settings)
        val weeklyAttempt = runCatching { when (settings.dataSource) {
            TwidgetStore.DATA_SOURCE_FXTWITTER -> ProviderFallback.directThenOptionalFallback(
                direct = { if (settings.shareHistory) fetchBridge(context, clean, endpoint)
                    else fetchFxTwitter(clean, TwidgetStore.currentStats(context, clean).userId) },
                fallback = if (settings.shareHistory) ({ fetchFxTwitter(clean, TwidgetStore.currentStats(context, clean).userId) }) else null,
            )
            TwidgetStore.DATA_SOURCE_TWITTERAPIS -> ProviderFallback.directThenOptionalFallback(
                direct = { fetchTwitterApis(context, clean) },
                fallback = { fetchBridge(context, clean, endpoint) },
            )
            else -> fetchBridge(context, clean, endpoint)
        } }
        val hallOfFame = runCatching {
            BangerClient.refresh(context, clean, settings, endpoint)
        }.getOrNull()
        if (hallOfFame != null && !hallOfFame.complete && !hallOfFame.capped) {
            BangerScanWorker.enqueue(context, clean)
        }
        var archiveCheckedAt = previous?.historicalCheckedAt ?: 0L
        var coverage = previous?.archiveCoverage ?: ArchiveCoverage()
        var archiveRoot: JSONObject? = null
        val history = if (settings.shareHistory || settings.dataSource in setOf(TwidgetStore.DATA_SOURCE_DEFAULT, TwidgetStore.DATA_SOURCE_SELF_HOSTED)) runCatching {
            val userId = TwidgetStore.currentStats(context, clean).userId
            if (userId.isBlank()) previous?.historicalPosts.orEmpty() else {
                val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
                val raw = read("${endpoint.url}/analytics/$encoded?all=true", endpoint.token, logContext = context)
                val root = JSONObject(raw)
                HistoricalPostArchive.requireIdentity(root, clean, userId)
                archiveRoot = root
                HistoricalPostArchive.parse(root, clean, userId).also {
                    archiveCheckedAt = System.currentTimeMillis()
                    coverage = HistoricalPostArchive.coverage(root)
                }
            }
        }.getOrElse { previous?.historicalPosts.orEmpty() } else emptyList()
        val weekly = weeklyAttempt.getOrElse { error ->
            if (history.isEmpty()) throw error
            // Archive counters can still feed general cards while a weekly read
            // is unavailable; old posts never become this week's winners.
            analyticsFromPosts(clean, TwidgetStore.currentStats(context, clean).followersCount, 0, true,
                emptyList(), cachedAt = System.currentTimeMillis())
        }
        val analytics = weekly.copy(
            historicalPosts = history,
            historicalCheckedAt = archiveCheckedAt,
            archiveCoverage = coverage,
            banger = hallOfFame?.post ?: previous?.banger,
            bangerComplete = hallOfFame?.complete ?: previous?.bangerComplete ?: false,
            bangerPostsScanned = hallOfFame?.postsScanned ?: previous?.bangerPostsScanned ?: 0,
            cachedAt = weekly.cachedAt,
        ).let { data -> archiveRoot?.let { root -> HistoricalPostArchive.merge(data, root, clean,
            TwidgetStore.currentStats(context, clean).userId, archiveCheckedAt) } ?: data }
            .let { it.copy(reviewPosts = ReviewPostRetention.merge(it, previous)) }
        val result = TwidgetStore.withAccountGeneration(context, clean, generation) {
            if (TwidgetStore.settings(context).dataSource == settings.dataSource) {
                writeWithObservations(context, clean, analytics)
                InteractionStore.recordAnalytics(context, clean, analytics)
            }
            analytics
        }
        if (settings.shareHistory) {
            val userId = TwidgetStore.currentStats(context, clean).userId
            runCatching { com.tjg.twidget.bridge.HistoryPool.syncInteractions(context, clean, userId, endpoint) }
        }
        HistoricalAnalyticsWorker.enqueue(context, clean)
        return result
    }

    /** A background archive page updates general cards and Brief without replacing weekly analytics. */
    internal fun cacheArchive(context: Context, username: String, root: JSONObject, userId: String) = synchronized(TwidgetStore) {
        HistoricalPostArchive.requireIdentity(root, username, userId)
        val previous = cached(context, username) ?: analyticsFromPosts(username,
            TwidgetStore.currentStats(context, username).followersCount, 0, true, emptyList(), cachedAt = 0L)
        val updated = HistoricalPostArchive.merge(previous, root, username, userId, System.currentTimeMillis())
        writeWithObservations(context, username, updated)
        InteractionStore.recordAnalytics(context, username, updated)
        val activity = com.tjg.twidget.data.DailyStreakStore.snapshot(context, username)
        com.tjg.twidget.data.DailyStreakStore.mergeOriginalActivity(context, username,
            updated.historicalPosts.filter { !it.isReply }.map { it.timestamp }.toSet(),
            activity.activityWindowStartAt, activity.activityCheckedAt, activity.activityComplete)
    }

    internal fun observations(context: Context, username: String, postUrl: String): List<PostObservation> {
        val root = prefs(context).getString(key(context, username), null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (!root?.optString("userName").equals(username.trim().trimStart('@'), true)) return emptyList()
        return PostObservationHistory.read(root?.optJSONArray(PostObservationHistory.FIELD), postUrl)
    }

    private fun writeWithObservations(context: Context, username: String, analytics: PostAnalytics) = synchronized(TwidgetStore) {
        val cacheKey = key(context, username)
        val previous = prefs(context).getString(cacheKey, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val previousAnalytics = previous?.let { runCatching { NetworkResponseParsers.parseBridgeAnalytics(it) }.getOrNull() }
        val withArchive = if (HistoricalAnalyticsWorker.allowed(TwidgetStore.settings(context)) && previousAnalytics != null) {
            val posts = (analytics.historicalPosts + previousAnalytics.historicalPosts).groupBy { it.url }
                .values.map { observations -> observations.maxBy { it.observedAt } }
                .sortedByDescending { it.timestamp }.take(2000)
            analytics.copy(historicalPosts = posts,
                archiveCoverage = if (previousAnalytics.historicalCheckedAt > analytics.historicalCheckedAt)
                    previousAnalytics.archiveCoverage else analytics.archiveCoverage,
                historicalCheckedAt = maxOf(analytics.historicalCheckedAt, previousAnalytics.historicalCheckedAt))
        } else analytics
        val retained = withArchive.copy(reviewPosts = ReviewPostRetention.merge(withArchive, previousAnalytics))
        val encoded = serialize(retained).put(PostObservationHistory.FIELD,
            PostObservationHistory.record(previous?.takeIf { it.optString("userName").equals(analytics.userName, true) }
                ?.optJSONArray(PostObservationHistory.FIELD), analytics))
        prefs(context).edit().putString(cacheKey, encoded.toString()).apply()
    }

    private fun fetchBridge(context: Context, username: String, endpoint: BridgeEndpoint): PostAnalytics {
        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val body = read("${endpoint.url}/analytics/$encoded", endpoint.token, logContext = context)
        return NetworkResponseParsers.parseBridgeAnalytics(JSONObject(body))
    }

    private fun fetchTwitterApis(context: Context, username: String): PostAnalytics {
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        val timeline = WeeklyTimelineRepository.twitterApis(context, username)
        val statuses = timeline.statuses

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
            bestCandidates = statuses.mapNotNull { parseTwitterApisPost(it, username, weekAgo, now, includeReplies = true) },
            cachedAt = timeline.checkedAt,
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

    /** Read-only public weekly analytics for comparisons; does not update account caches. */
    internal fun fetchPublic(username: String): PostAnalytics = fetchFxTwitter(username)

    private fun fetchFxTwitter(username: String, userId: String = ""): PostAnalytics {
        val profile = if (userId.matches(Regex("[0-9]+"))) FxTwitterClient.fetchProfileById(userId) else FxTwitterClient.fetchProfile(username)
        require(profile.userName.equals(username, true)) { "Account handle changed; refresh the profile first." }
        val now = System.currentTimeMillis()
        val weekAgo = now - WEEK_MS
        val timeline = WeeklyTimelineRepository.fxTwitter(username, profile.userId)
        val statuses = timeline.statuses
        val requested = username.lowercase(Locale.US)
        val posts = statuses
            .mapNotNull { status -> status.takeIf { isOwnWeeklyPost(it, requested, weekAgo, now) }?.let(::parseFxPost) }
            .sortedByDescending { it.timestamp }
        return analyticsFromPosts(
            username = username,
            followers = profile.followersCount,
            statusesInspected = statuses.size,
            sampled = timeline.incomplete,
            posts = posts,
            bestCandidates = statuses.filter { isOwnWeeklyPost(it, requested, weekAgo, now, includeReplies = true) }.map(::parseFxPost),
            cachedAt = timeline.checkedAt,
        )
    }

    internal fun parseTwitterApisPost(
        tweet: JSONObject,
        requestedUsername: String,
        weekAgo: Long,
        now: Long,
        includeReplies: Boolean = false,
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
        if ((!includeReplies && isReply) || isRepost || timestamp !in weekAgo..(now + 5 * 60_000L)) return null
        if (!includeReplies && conversationId.isNotBlank() && id.isNotBlank() && conversationId != id) return null

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
            viewsKnown = listOf("view_count", "viewCount", "views").any { tweet.has(it) && !tweet.isNull(it) },
            likesKnown = listOf("favorite_count", "favourite_count", "like_count", "likeCount", "likes").any { tweet.has(it) && !tweet.isNull(it) },
            repostsKnown = listOf("retweet_count", "repost_count", "retweetCount", "repostCount", "reposts").any { tweet.has(it) && !tweet.isNull(it) },
            repliesKnown = listOf("reply_count", "replyCount", "replies").any { tweet.has(it) && !tweet.isNull(it) },
            quotesKnown = listOf("quote_count", "quoteCount", "quotes").any { tweet.has(it) && !tweet.isNull(it) },
            bookmarks = tweetLong(tweet, "bookmark_count", "bookmarkCount", "bookmarks").takeIf {
                listOf("bookmark_count", "bookmarkCount", "bookmarks").any { key -> tweet.has(key) && !tweet.isNull(key) } },
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
            fullText = tweetString(tweet, "text", "full_text").takeIf {
                it.length <= 10_000 && !tweetBoolean(tweet, "truncated", "isTruncated")
            },
            isReply = isReply || (conversationId.isNotBlank() && id.isNotBlank() && conversationId != id),
            conversationId = conversationId.takeIf { it.matches(Regex("[0-9]+")) }.orEmpty(),
            replyToId = tweetString(tweet, "in_reply_to_status_id", "in_reply_to_status_id_str", "inReplyToId", "in_reply_to_tweet_id").takeIf { it.matches(Regex("[0-9]+")) }.orEmpty(),
            quotedPost = (tweet.optJSONObject("quoted_tweet") ?: tweet.optJSONObject("quoted_status") ?: tweet.optJSONObject("quote"))?.let { quoted ->
                val source = quoted.optJSONObject("author") ?: quoted.optJSONObject("user") ?: JSONObject()
                val handle = tweetString(source, "userName", "username", "screen_name")
                val full = tweetString(quoted, "full_text", "text")
                QuotedPost(safeWebUrl(tweetString(quoted, "url", "tweet_url", "twitterUrl")).ifBlank {
                    tweetString(quoted, "id", "tweet_id", "rest_id").takeIf { it.matches(Regex("\\d+")) }
                        ?.let { "https://x.com/i/web/status/$it" }.orEmpty()
                }, full.take(10_000), tweetString(source, "name"), handle,
                    safeWebUrl(tweetString(source, "profilePicture", "profile_image_url_https", "profile_image_url", "avatar_url")),
                    parseTwitterApisMedia(quoted).take(4), full.length <= 10_000 && !tweetBoolean(quoted, "truncated", "isTruncated"), twitterApisTimestamp(quoted))
                    .takeIf { it.url.isNotBlank() || it.text.isNotBlank() || it.media.isNotEmpty() }
            },
        )
    }

    internal fun analyticsFromPosts(
        username: String,
        followers: Long,
        statusesInspected: Int,
        sampled: Boolean,
        posts: List<PostSummary>,
        bestCandidates: List<PostSummary> = posts,
        cachedAt: Long,
    ): PostAnalytics {
        val views = posts.map { it.views }
        val engagements = posts.map { it.engagements }
        val likes = posts.map { it.likes }
        val replies = posts.map { it.replies }
        val totalViews = views.sum()
        val totalEngagements = engagements.sum()
        val count = posts.size
        val maturePosts = posts.filter { it.timestamp > 0L && cachedAt - it.timestamp >= WORST_POST_MATURITY_MS }
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
            best = bestCandidates.maxByOrNull { it.engagements },
            worst = maturePosts.minByOrNull { it.engagements }.takeIf { maturePosts.size >= 2 },
            cachedAt = cachedAt,
            medianLikes = median(likes),
            medianReplies = median(replies),
            recentPosts = posts.take(50),
            recentReplies = bestCandidates.filter(PostSummary::isReply)
                .distinctBy(PostSummary::url).sortedByDescending(PostSummary::timestamp).take(50),
        )
    }

    internal fun isOwnWeeklyPost(status: JSONObject, username: String, weekAgo: Long, now: Long, includeReplies: Boolean = false): Boolean =
        FxPostPolicy.isOwnInWindow(
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
            includeReplies,
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
            fullText = status.optString("text").takeIf {
                it.length <= 10_000 && !status.optBoolean("truncated", false)
            },
            isReply = hasValue(status, "replying_to") || hasValue(status, "in_reply_to_status_id") ||
                hasValue(status, "in_reply_to_status_id_str") ||
                (hasValue(status, "conversation_id") && hasValue(status, "id") &&
                    status.optString("conversation_id") != status.optString("id")),
            conversationId = status.optString("conversation_id").takeIf { it.matches(Regex("[0-9]+")) }.orEmpty(),
            replyToId = status.optString("in_reply_to_status_id").ifBlank { status.optString("in_reply_to_status_id_str") }.ifBlank { status.optString("replying_to_status") }.takeIf { it.matches(Regex("[0-9]+")) }.orEmpty(),
            views = status.optLong("views"),
            viewsKnown = status.has("views") && !status.isNull("views"),
            likesKnown = status.has("likes") && !status.isNull("likes"),
            repostsKnown = status.has("reposts") && !status.isNull("reposts"),
            repliesKnown = status.has("replies") && !status.isNull("replies"),
            quotesKnown = status.has("quotes") && !status.isNull("quotes"),
            bookmarks = status.optLong("bookmarks").takeIf { status.has("bookmarks") && !status.isNull("bookmarks") && it >= 0 },
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
            quotedPost = status.optJSONObject("quote")?.let { quoted ->
                val source = quoted.optJSONObject("author") ?: JSONObject()
                val full = quoted.optString("text")
                QuotedPost(safeWebUrl(quoted.optString("url")), full.take(10_000), source.optString("name"), source.optString("screen_name"),
                    safeWebUrl(source.optString("avatar_url")), parseFxMedia(quoted.optJSONObject("media")?.optJSONArray("all")).take(4),
                    full.length <= 10_000 && !quoted.optBoolean("truncated"), fxTimestamp(quoted))
                    .takeIf { it.url.isNotBlank() || it.text.isNotBlank() || it.media.isNotEmpty() }
            },
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
                    playbackUrl = safeWebUrl(item.optString("url")).takeIf { item.optString("type") !in listOf("photo", "image") }.orEmpty(),
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

    internal fun serialize(a: PostAnalytics): JSONObject = JSONObject()
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
        .put(
            "benchmarks",
            JSONObject()
                .put("medianLikes", a.medianLikes)
                .put("medianReplies", a.medianReplies),
        )
        .apply {
            a.best?.let { put("best", serializePost(it)) }
            a.worst?.let { put("worst", serializePost(it)) }
            a.banger?.let { put("banger", serializePost(it)) }
            put("recentPosts", JSONArray(a.recentPosts.map(::serializePost)))
            put("recentReplies", JSONArray(a.recentReplies.map(::serializePost)))
            put("reviewPosts", JSONArray(a.reviewPosts.map(::serializePost)))
            put("historicalPosts", JSONArray(a.historicalPosts.map(::serializePost)))
            put("historicalCheckedAt", a.historicalCheckedAt)
            put("archiveState", JSONObject().put("postCount", a.archiveCoverage.postCount).put("replyCount", a.archiveCoverage.replyCount)
                .put("backfill", JSONObject().put("status", a.archiveCoverage.status).put("oldestPostAt", a.archiveCoverage.scannedThroughAt))
                .put("coverage", JSONObject().put("earliestPostAt", a.archiveCoverage.earliestPostAt)
                    .put("latestPostAt", a.archiveCoverage.latestPostAt)
                    .put("replyCoverage", if (a.archiveCoverage.replyInclusive) "reply_inclusive_timeline" else "incidental_or_unknown")))
            put("bangerComplete", a.bangerComplete)
            put("bangerPostsScanned", a.bangerPostsScanned)
        }

    internal fun serializePost(p: PostSummary): JSONObject = JSONObject()
        .put("url", p.url)
        .put("text", p.text)
        .put("fullText", p.fullText ?: JSONObject.NULL)
        .put("quotedPost", p.quotedPost?.let(::serializeQuotedPost) ?: JSONObject.NULL)
        .put("observedAt", p.observedAt).put("isReply", p.isReply).put("conversationId", p.conversationId).put("replyToId", p.replyToId)
        .put("bookmarks", p.bookmarks ?: JSONObject.NULL).put("bookmarksKnown", p.bookmarks != null)
        .put("viewsKnown", p.viewsKnown)
        .put("likesKnown", p.likesKnown)
        .put("repostsKnown", p.repostsKnown)
        .put("repliesKnown", p.repliesKnown)
        .put("quotesKnown", p.quotesKnown)
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

    internal fun serializeQuotedPost(post: QuotedPost): JSONObject = JSONObject()
        .put("url", post.url).put("text", post.text).put("textComplete", post.textComplete).put("ts", post.timestamp)
        .put("authorName", post.authorName).put("authorUserName", post.authorUserName).put("authorAvatar", post.authorAvatar)
        .put("media", JSONArray(post.media.take(4).map(::serializeMedia)))

    private fun serializeLink(link: PostLink): JSONObject = JSONObject()
        .put("display", link.display)
        .put("url", link.url)

    private fun serializeMedia(media: PostMedia): JSONObject = JSONObject()
        .put("type", media.type)
        .put("url", media.url).put("playbackUrl", media.playbackUrl)
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
        com.tjg.twidget.data.AccountPreferences.open(context, PREFS)
}
