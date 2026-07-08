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
        if (bridgeUrl.isBlank()) {
            val current = TwidgetStore.currentStats(context, username)
            return current.copy(
                fullName = current.fullName.takeIf { it.isNotBlank() && it != "Twidget" } ?: username,
                userName = username,
                profileImage = current.profileImage.ifBlank { fallbackAvatarUrl(username) },
                syncedAt = System.currentTimeMillis(),
            )
        }

        var lastError: Exception? = null
        val useXApi = settings.dataSource == TwidgetStore.DATA_SOURCE_X_API &&
            XApiClient.hasCredentials(settings)

        // X API source calls api.x.com directly from the device; the bridge is
        // only a fallback. Other sources go through the bridge first and fall
        // back to the X API when the user has configured credentials.
        if (useXApi) {
            try {
                return finalize(XApiClient.fetchProfile(context, username), username)
            } catch (error: Exception) {
                lastError = error
            }
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
            }
        }

        if (!useXApi && XApiClient.hasCredentials(settings)) {
            try {
                return finalize(XApiClient.fetchProfile(context, username), username)
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
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $body")
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
            statusesCount = findLong(user, setOf("statusesCount", "statusCount", "tweetCount", "tweetsCount", "statuses_count", "listed_count")),
            likeCount = findLong(user, setOf("likeCount", "likesCount", "favouritesCount", "favoritesCount", "favourites_count", "favorites_count")),
            profileImage = profileImageUrl(user),
            isVerified = findBoolean(user, setOf("isVerified", "verified", "blueVerified", "blue_verified", "is_blue_verified", "verifiedType", "verified_type")),
            isPrivate = findBoolean(user, setOf("isPrivate", "private", "protected", "isProtected", "is_protected", "protectedProfile")),
            history = parseHistory(json, user),
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
                HistorySample(
                    dayLabel = sample.optString("dayLabel", ""),
                    followers = sample.optLong("followers", 0L),
                    following = sample.optLong("following", sample.optLong("followings", 0L)),
                    posts = sample.optLong("posts", 0L),
                    likes = sample.optLong("likes", 0L),
                    timestamp = timestamp,
                    estimated = sample.optBoolean("est", false),
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
            .replace(Regex("([?&]name=)normal(?=(&|$))"), "${'$'}1400x400")

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
}
