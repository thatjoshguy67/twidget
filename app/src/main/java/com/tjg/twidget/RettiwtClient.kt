package com.tjg.twidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

object RettiwtClient {
    fun refresh(context: Context, accountUsername: String = TwidgetStore.settings(context).username): ProfileStats {
        val settings = TwidgetStore.settings(context)
        val username = accountUsername.trim().trimStart('@')
        if (username.isBlank()) {
            throw IllegalStateException("Username is required before syncing")
        }
        val bridgeUrl = effectiveBridgeUrl(settings)
        var lastError: Exception? = null
        val useXApi = settings.dataSource == TwidgetStore.DATA_SOURCE_X_API &&
            XApiClient.hasCredentials(settings)

        // X API source calls api.x.com directly from the device; the bridge is
        // only a fallback. Other sources go through the bridge first and fall
        // back to the X API when the user has configured credentials.
        if (useXApi) {
            try {
                return finalize(withAvailableLikes(context, username, XApiClient.fetchProfile(context, username)), username)
            } catch (error: Exception) {
                lastError = error
            }
        }

        // FxTwitter source hits the public api.fxtwitter.com directly from the
        // device; the bridge below stays as its fallback and, when the user
        // has opted in, contributes to and serves the shared history pool.
        if (settings.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER) {
            try {
                val stats = finalize(FxTwitterClient.fetchProfile(username), username)
                return HistoryPool.enrich(context, stats, settings, bridgeUrl)
            } catch (error: Exception) {
                lastError = error
            }
        }

        // Direct providers above do not need a bridge. If one is unavailable
        // and no fallback bridge was configured, preserve the real failure
        // instead of marking stale cached numbers as freshly synced.
        if (bridgeUrl.isBlank()) {
            throw lastError ?: IllegalStateException("No data provider is configured")
        }

        val encoded = URLEncoder.encode(username, StandardCharsets.UTF_8.name())
        val candidates = listOf(
            "$bridgeUrl/user/$encoded",
            "$bridgeUrl/users/$encoded",
            "$bridgeUrl/details/$encoded",
            "$bridgeUrl?username=$encoded",
        )
        for (candidate in candidates) {
            try {
                return enrichStatusFields(
                    context,
                    finalize(parseUser(read(candidate, settings.apiKey)), username),
                    settings,
                    bridgeUrl,
                )
            } catch (error: Exception) {
                lastError = error
                // Compatibility aliases are only useful when an older
                // self-hosted bridge says the route does not exist. Retrying
                // the same failing upstream four times can otherwise stall a
                // refresh for roughly 40 seconds.
                if (error !is HttpError || error.code !in setOf(404, 405)) break
            }
        }

        if (!useXApi && XApiClient.hasCredentials(settings)) {
            try {
                return finalize(withAvailableLikes(context, username, XApiClient.fetchProfile(context, username)), username)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("Unable to fetch Rettiwt profile")
    }

    // Rettiwt doesn't report protected/verified status reliably. Fill unknown
    // status fields from an official bridge endpoint when available, or from
    // local X API credentials as a final source.
    private fun enrichStatusFields(
        context: Context,
        parsed: ProfileStats,
        settings: TwidgetSettings,
        bridgeUrl: String,
    ): ProfileStats {
        if (parsed.isPrivate != null && parsed.isVerified != null) return parsed
        bridgeOfficialStatus(parsed.userName, settings, bridgeUrl)?.let { official ->
            return parsed.copy(
                isVerified = parsed.isVerified ?: official.isVerified,
                isPrivate = parsed.isPrivate ?: official.isPrivate,
            )
        }
        runCatching { FxTwitterClient.fetchProfile(parsed.userName) }
            .getOrNull()
            ?.takeIf { it.isVerified != null || it.isPrivate != null }
            ?.let { fx ->
                return parsed.copy(
                    isVerified = parsed.isVerified ?: fx.isVerified,
                    isPrivate = parsed.isPrivate ?: fx.isPrivate,
                )
            }
        if (!XApiClient.hasCredentials(settings)) return parsed
        return runCatching {
            val official = XApiClient.fetchProfile(context, parsed.userName)
            parsed.copy(
                isVerified = parsed.isVerified ?: official.isVerified,
                isPrivate = parsed.isPrivate ?: official.isPrivate,
            )
        }.getOrDefault(parsed)
    }

    private fun bridgeOfficialStatus(username: String, settings: TwidgetSettings, bridgeUrl: String): ProfileStats? {
        if (bridgeUrl.isBlank()) return null
        val encoded = URLEncoder.encode(username.trim().trimStart('@'), StandardCharsets.UTF_8.name())
        return runCatching {
            parseUser(read("$bridgeUrl/official/user/$encoded", settings.apiKey))
        }.getOrNull()?.takeIf { it.isPrivate != null || it.isVerified != null }
    }

    private fun finalize(parsed: ProfileStats, requestedUsername: String): ProfileStats {
        val username = parsed.userName.ifBlank { requestedUsername }
        return parsed.copy(
            fullName = parsed.fullName.ifBlank { requestedUsername },
            userName = username,
            profileImage = highResolutionProfileImageUrl(parsed.profileImage).ifBlank { fallbackAvatarUrl(username) },
            syncedAt = System.currentTimeMillis(),
        )
    }

    // X user public_metrics has no profile-wide likes count. Use FxTwitter for
    // that one field when possible, otherwise retain the last observed value
    // instead of recording a fabricated drop to zero.
    private fun withAvailableLikes(context: Context, username: String, official: ProfileStats): ProfileStats {
        val live = runCatching { FxTwitterClient.fetchProfile(username) }.getOrNull()
        val previous = TwidgetStore.currentStats(context, username)
        val likes = MetricProvenance.preferLiveThenPrevious(
            liveValue = live?.likeCount,
            liveKnown = live?.likesKnown == true,
            previousValue = previous.likeCount,
            previousKnown = previous.likesKnown,
        )
        return official.copy(likeCount = likes.value, likesKnown = likes.known)
    }

    private fun effectiveBridgeUrl(settings: TwidgetSettings): String =
        when (settings.dataSource) {
            TwidgetStore.DATA_SOURCE_SELF_HOSTED,
            TwidgetStore.DATA_SOURCE_X_API -> settings.bridgeUrl.trim().trimEnd('/')
            else -> settings.bridgeUrl.trim().trimEnd('/')
        }

    private fun read(url: String, apiKey: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) {
            connection.setRequestProperty("X-Rettiwt-Api-Key", apiKey)
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        if (code !in 200..299) throw HttpError(code, "Bridge HTTP $code: ${body.take(300)}")
        return body
    }

    private fun parseUser(body: String): ProfileStats {
        val root = body.trim()
        val json = when {
            root.startsWith("[") -> (JSONArray(root).opt(0) as? JSONObject) ?: JSONObject()
            else -> JSONObject(root)
        }
        val user = userObject(json)

        return ProfileStats(
            fullName = findString(user, setOf("fullName", "name", "displayName", "display_name")).orEmpty(),
            userName = findString(user, setOf("userName", "username", "screenName", "screen_name", "handle")).orEmpty().trimStart('@'),
            followersCount = findLong(user, setOf("followersCount", "followers_count", "followerCount", "follower_count")),
            followingsCount = findLong(user, setOf("followingsCount", "followingCount", "friends_count", "following_count")),
            statusesCount = findLong(user, setOf("statusesCount", "statusCount", "tweetCount", "tweetsCount", "statuses_count")),
            likeCount = findLong(user, setOf("likeCount", "likesCount", "favouritesCount", "favoritesCount", "favourites_count", "favorites_count")),
            profileImage = profileImageUrl(user),
            isVerified = findBoolean(user, setOf("isVerified", "verified", "blueVerified", "blue_verified", "is_blue_verified", "verifiedType", "verified_type")),
            isPrivate = findBoolean(user, setOf("isPrivate", "private", "protected", "isProtected", "is_protected", "protectedProfile")),
            history = parseHistory(json, user),
            followersKnown = hasLong(user, setOf("followersCount", "followers_count", "followerCount", "follower_count")),
            followingKnown = hasLong(user, setOf("followingsCount", "followingCount", "friends_count", "following_count")),
            postsKnown = hasLong(user, setOf("statusesCount", "statusCount", "tweetCount", "tweetsCount", "statuses_count")),
            likesKnown = hasLong(user, setOf("likeCount", "likesCount", "favouritesCount", "favoritesCount", "favourites_count", "favorites_count")),
        )
    }

    private fun parseHistory(root: JSONObject, user: JSONObject): List<HistorySample> {
        val array = root.optJSONArray("history")
            ?: user.optJSONArray("history")
            ?: return emptyList()
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

    private fun profileImageUrl(user: JSONObject): String =
        highResolutionProfileImageUrl(findString(
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
            )
        ).orEmpty())

    private fun highResolutionProfileImageUrl(url: String): String =
        url.trim()
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))")) { "${it.groupValues[1]}400x400" }

    private fun userObject(json: JSONObject): JSONObject =
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
                is JSONArray -> {
                    for (itemIndex in 0 until value.length()) {
                        (value.opt(itemIndex) as? JSONObject)?.let { child ->
                            findString(child, keys, depth + 1)?.let { return it }
                        }
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
                is JSONArray -> {
                    for (itemIndex in 0 until value.length()) {
                        (value.opt(itemIndex) as? JSONObject)?.let { child ->
                            findLong(child, keys, depth + 1).takeIf { it > 0 }?.let { return it }
                        }
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
                is JSONArray -> {
                    for (itemIndex in 0 until value.length()) {
                        (value.opt(itemIndex) as? JSONObject)?.let { child ->
                            findBoolean(child, keys, depth + 1)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun fallbackAvatarUrl(username: String): String =
        "https://unavatar.io/twitter/${URLEncoder.encode(username.trimStart('@'), StandardCharsets.UTF_8.name())}"

    private fun localDayFromServer(timestamp: Long, dayLabel: String): Long =
        HistoryDates.localDayFromServer(timestamp, dayLabel)

    private class HttpError(val code: Int, message: String) : IllegalStateException(message)
}
