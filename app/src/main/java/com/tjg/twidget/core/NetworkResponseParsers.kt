package com.tjg.twidget.core

import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostLink
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.bridge.BridgeAnalyticsImport
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.schedule.json
import org.json.JSONArray
import org.json.JSONObject

/** Testable JSON parsers shared by Twidget network clients. */
internal object NetworkResponseParsers {
    fun parseFxTwitterUser(json: JSONObject, fallbackUsername: String): ProfileStats {
        val user = json.optJSONObject("user")
            ?: throw IllegalStateException(
                "FxTwitter returned no user for @$fallbackUsername" +
                    json.optString("message")
                        .takeIf { it.isNotBlank() && it != "OK" }
                        ?.let { " ($it)" }
                        .orEmpty()
            )
        return ProfileStats(
            fullName = user.optString("name"),
            userName = user.optString("screen_name").trimStart('@'),
            followersCount = user.optLong("followers"),
            followingsCount = user.optLong("following"),
            statusesCount = user.optLong("tweets"),
            likeCount = user.optLong("likes"),
            profileImage = user.optString("avatar_url"),
            isVerified = user.optJSONObject("verification")?.optBoolean("verified", false)
                ?: if (user.has("verified")) user.optBoolean("verified") else null,
            isPrivate = if (user.has("protected")) user.optBoolean("protected") else null,
            syncedAt = System.currentTimeMillis(),
            followersKnown = user.has("followers") && !user.isNull("followers"),
            followingKnown = user.has("following") && !user.isNull("following"),
            postsKnown = user.has("tweets") && !user.isNull("tweets"),
            likesKnown = user.has("likes") && !user.isNull("likes"),
        )
    }

    fun parseXApiUser(json: JSONObject, fallbackUsername: String): ProfileStats {
        val user = json.optJSONObject("data")
            ?: throw IllegalStateException(
                json.optJSONArray("errors")?.optJSONObject(0)?.optString("detail")
                    ?.takeIf { it.isNotBlank() } ?: "X API returned no user for @$fallbackUsername"
            )
        val metrics = user.optJSONObject("public_metrics") ?: JSONObject()
        return ProfileStats(
            fullName = user.optString("name").ifBlank { fallbackUsername },
            userName = user.optString("username").ifBlank { fallbackUsername }.trimStart('@'),
            followersCount = metrics.optLong("followers_count"),
            followingsCount = metrics.optLong("following_count"),
            statusesCount = metrics.optLong("tweet_count"),
            likeCount = 0,
            profileImage = highResolutionProfileImageUrl(user.optString("profile_image_url")),
            isVerified = if (user.has("verified") || user.has("verified_type")) {
                user.optBoolean("verified", false) ||
                    user.optString("verified_type").let { it.isNotBlank() && it != "none" }
            } else {
                null
            },
            isPrivate = if (user.has("protected")) user.optBoolean("protected") else null,
            syncedAt = System.currentTimeMillis(),
            followersKnown = metrics.has("followers_count"),
            followingKnown = metrics.has("following_count"),
            postsKnown = metrics.has("tweet_count"),
            likesKnown = false,
        )
    }

    fun parseBridgeProfile(body: String): ProfileStats {
        val root = body.trim()
        val json = when {
            root.startsWith("[") -> (JSONArray(root).opt(0) as? JSONObject) ?: JSONObject()
            else -> JSONObject(root)
        }
        val user = bridgeUserObject(json)
        return ProfileStats(
            fullName = findString(user, setOf("fullName", "name", "displayName", "display_name")).orEmpty(),
            userName = findString(user, setOf("userName", "username", "screenName", "screen_name", "handle")).orEmpty().trimStart('@'),
            followersCount = findLong(user, setOf("followersCount", "followers_count", "followerCount", "follower_count")),
            followingsCount = findLong(user, setOf("followingsCount", "followingCount", "friends_count", "following_count")),
            statusesCount = findLong(user, setOf("statusesCount", "statusCount", "tweetCount", "tweetsCount", "statuses_count")),
            likeCount = findLong(user, setOf("likeCount", "likesCount", "favouritesCount", "favoritesCount", "favourites_count", "favorites_count")),
            profileImage = bridgeProfileImageUrl(user),
            isVerified = findBoolean(user, setOf("isVerified", "verified", "blueVerified", "blue_verified", "is_blue_verified", "verifiedType", "verified_type")),
            isPrivate = findBoolean(user, setOf("isPrivate", "private", "protected", "isProtected", "is_protected", "protectedProfile")),
            history = parseBridgeHistory(json, user),
            followersKnown = hasLong(user, setOf("followersCount", "followers_count", "followerCount", "follower_count")),
            followingKnown = hasLong(user, setOf("followingsCount", "followingCount", "friends_count", "following_count")),
            postsKnown = hasLong(user, setOf("statusesCount", "statusCount", "tweetCount", "tweetsCount", "statuses_count")),
            likesKnown = hasLong(user, setOf("likeCount", "likesCount", "favouritesCount", "favoritesCount", "favourites_count", "favorites_count")),
        )
    }

    fun parseBridgeHistoryArray(array: JSONArray): List<HistorySample> =
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
                    timestamp = HistoryDates.localDayFromServer(timestamp, dayLabel),
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

    fun parseBridgeAnalyticsImport(root: JSONObject): BridgeAnalyticsImport =
        BridgeAnalyticsImport(
            accepted = root.optInt("accepted", 0),
            checkedAnchors = root.optInt("checkedAnchors", 0),
            history = parseBridgeHistoryArray(root.optJSONArray("history") ?: JSONArray()),
        )

    fun parseBridgeAnalytics(json: JSONObject): PostAnalytics {
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
            best = parseBridgePost(json.optJSONObject("best")),
            worst = parseBridgePost(json.optJSONObject("worst")),
            banger = parseBridgePost(json.optJSONObject("banger")),
            bangerComplete = json.optBoolean("bangerComplete", false),
            bangerPostsScanned = json.optInt("bangerPostsScanned", 0),
            cachedAt = json.optLong("cachedAt", System.currentTimeMillis()),
        )
    }

    private fun parseBridgeHistory(root: JSONObject, user: JSONObject): List<HistorySample> {
        val array = root.optJSONArray("history") ?: user.optJSONArray("history") ?: return emptyList()
        return parseBridgeHistoryArray(array).map { it.copy(imported = false, sharedImport = false) }
    }

    private fun parseBridgePost(json: JSONObject?): PostSummary? {
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
            links = parseBridgeLinks(json.optJSONArray("links")),
            media = parseBridgeMedia(json.optJSONArray("media")),
        )
    }

    private fun parseBridgeLinks(array: JSONArray?): List<PostLink> {
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

    private fun parseBridgeMedia(array: JSONArray?): List<PostMedia> {
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

    private fun bridgeProfileImageUrl(user: JSONObject): String =
        highResolutionProfileImageUrl(
            findString(
                user,
                setOf(
                    "profileImage",
                    "profile_image_url_https",
                    "profile_image_url",
                    "avatar",
                    "avatarUrl",
                    "avatar_url",
                    "imageUrl",
                    "image_url",
                ),
            ).orEmpty(),
        )

    private fun highResolutionProfileImageUrl(url: String): String =
        url.trim()
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))")) { "${it.groupValues[1]}400x400" }

    private fun safeWebUrl(value: String): String = runCatching {
        java.net.URI(value.trim()).takeIf { it.scheme == "https" || it.scheme == "http" }?.toString().orEmpty()
    }.getOrDefault("")

    private fun bridgeUserObject(json: JSONObject): JSONObject =
        json.optJSONObject("user")
            ?: json.optJSONObject("data")
            ?: json.optJSONObject("details")
            ?: json.optJSONObject("result")
            ?: json.optJSONObject("legacy")
            ?: json

    private fun findString(json: JSONObject, keys: Set<String>, depth: Int = 0): String? {
        if (depth > 4) return null
        keys.firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }?.let { return it }
        val names = json.names() ?: return null
        for (index in 0 until names.length()) {
            when (val value = json.opt(names.getString(index))) {
                is JSONObject -> findString(value, keys, depth + 1)?.let { return it }
                is JSONArray -> for (itemIndex in 0 until value.length()) {
                    (value.opt(itemIndex) as? JSONObject)?.let { child ->
                        findString(child, keys, depth + 1)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun findLong(json: JSONObject, keys: Set<String>, depth: Int = 0): Long {
        if (depth > 4) return 0
        keys.firstNotNullOfOrNull { key ->
            when (val value = json.opt(key)) {
                is Number -> value.toLong()
                is String -> value.filter { it.isDigit() }.toLongOrNull()
                else -> null
            }
        }?.let { return it }
        val names = json.names() ?: return 0
        for (index in 0 until names.length()) {
            when (val value = json.opt(names.getString(index))) {
                is JSONObject -> findLong(value, keys, depth + 1).takeIf { it > 0 }?.let { return it }
                is JSONArray -> for (itemIndex in 0 until value.length()) {
                    (value.opt(itemIndex) as? JSONObject)?.let { child ->
                        findLong(child, keys, depth + 1).takeIf { it > 0 }?.let { return it }
                    }
                }
            }
        }
        return 0
    }

    private fun hasLong(json: JSONObject, keys: Set<String>, depth: Int = 0): Boolean {
        if (depth > 4) return false
        if (keys.any { key ->
                when (val value = json.opt(key)) {
                    is Number -> true
                    is String -> value.filter { it.isDigit() }.toLongOrNull() != null
                    else -> false
                }
            }
        ) return true
        val names = json.names() ?: return false
        for (index in 0 until names.length()) {
            when (val value = json.opt(names.getString(index))) {
                is JSONObject -> if (hasLong(value, keys, depth + 1)) return true
                is JSONArray -> for (itemIndex in 0 until value.length()) {
                    val child = value.opt(itemIndex) as? JSONObject ?: continue
                    if (hasLong(child, keys, depth + 1)) return true
                }
            }
        }
        return false
    }

    private fun findBoolean(json: JSONObject, keys: Set<String>, depth: Int = 0): Boolean? {
        if (depth > 4) return null
        keys.firstNotNullOfOrNull { key ->
            when (val value = json.opt(key)) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.lowercase()) {
                    "true", "1", "yes" -> true
                    "false", "0", "no" -> false
                    "blue", "business", "government" -> true
                    else -> null
                }
                else -> null
            }
        }?.let { return it }
        val names = json.names() ?: return null
        for (index in 0 until names.length()) {
            when (val value = json.opt(names.getString(index))) {
                is JSONObject -> findBoolean(value, keys, depth + 1)?.let { return it }
                is JSONArray -> for (itemIndex in 0 until value.length()) {
                    (value.opt(itemIndex) as? JSONObject)?.let { child ->
                        findBoolean(child, keys, depth + 1)?.let { return it }
                    }
                }
            }
        }
        return null
    }
}
