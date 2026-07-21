package com.tjg.twidget.providers

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.followers.TopFollower
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class TopFollowersPage(val users: List<TopFollower>, val nextCursor: String)

data class TwitterApisTimelinePage(
    val tweets: List<JSONObject>,
    val nextCursor: String,
    val hasMore: Boolean,
)

enum class TwitterApisAccessSource {
    PERSONAL,
    APP_DEFAULT,
}

data class TwitterApisAccess(
    val apiKey: String,
    val source: TwitterApisAccessSource,
)

object TwitterApisClient {
    const val WEBSITE_URL = "https://twitterapis.com"
    private const val ENDPOINT = "https://api.twitterapis.com/twitter/user/followers_v2"
    private const val PROFILE_ENDPOINT = "https://api.twitterapis.com/twitter/user/info"
    private const val TIMELINE_ENDPOINT = "https://api.twitterapis.com/twitter/user/tweets"

    /** Full profile-provider access remains bring-your-own-key only. */
    fun hasCredentials(context: Context): Boolean =
        SecureCredentialStore.read(context, SecureCredentialStore.TWITTERAPIS_API_KEY).isNotBlank()

    fun hasTopFollowersAccess(context: Context): Boolean = topFollowersAccess(context) != null

    fun topFollowersAccess(context: Context): TwitterApisAccess? = selectTopFollowersAccess(
        personalKey = SecureCredentialStore.read(context, SecureCredentialStore.TWITTERAPIS_API_KEY),
        appDefaultKey = context.getString(R.string.twitterapis_default_api_key),
    )

    internal fun selectTopFollowersAccess(personalKey: String, appDefaultKey: String): TwitterApisAccess? {
        val personal = personalKey.trim()
        if (personal.isNotBlank()) return TwitterApisAccess(personal, TwitterApisAccessSource.PERSONAL)
        return appDefaultKey.trim().takeIf(String::isNotBlank)?.let {
            TwitterApisAccess(it, TwitterApisAccessSource.APP_DEFAULT)
        }
    }

    fun fetchProfile(context: Context, username: String): ProfileStats {
        val apiKey = SecureCredentialStore.read(context, SecureCredentialStore.TWITTERAPIS_API_KEY)
        require(apiKey.isNotBlank()) { "TwitterAPIs key is not configured" }
        val url = "$PROFILE_ENDPOINT?username=${encode(username.trim().trimStart('@'))}"
        val response = HttpTransport.get(
            url,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            userAgent = "Twidget (Android)",
        )
        return parseProfile(HttpTransport.requireSuccess(response, "TwitterAPIs"), username)
    }

    internal fun parseProfile(body: String, requestedUsername: String): ProfileStats {
        val root = JSONObject(body)
        val data = root.optJSONObject("data")
        val userContainer = data?.optJSONObject("user") ?: root.optJSONObject("user")
        val user = userContainer?.optJSONObject("result")
            ?: data?.optJSONObject("result")
            ?: root.optJSONObject("result")
            ?: userContainer
            ?: data
            ?: root
        val legacy = user.optJSONObject("legacy")
        val metrics = user.optJSONObject("public_metrics")
        val sources = listOfNotNull(user, legacy, metrics)
        fun string(vararg names: String): String = sources.firstNotNullOfOrNull { source ->
            names.firstNotNullOfOrNull { name -> source.optString(name).takeIf(String::isNotBlank) }
        }.orEmpty()
        fun count(vararg names: String): Long = sources.firstNotNullOfOrNull { source ->
            names.firstNotNullOfOrNull { name ->
                source.takeIf { it.has(name) && !it.isNull(name) }?.optLong(name)
            }
        } ?: 0L
        fun known(vararg names: String): Boolean = sources.any { source ->
            names.any { source.has(it) && !source.isNull(it) }
        }
        fun boolean(vararg names: String): Boolean? = sources.firstNotNullOfOrNull { source ->
            names.firstNotNullOfOrNull { name ->
                source.takeIf { it.has(name) && !it.isNull(name) }?.optBoolean(name)
            }
        }
        val handle = string("username", "screen_name", "userName")
            .ifBlank { requestedUsername }.trimStart('@')
        require(handle.isNotBlank() && (known("followers_count", "followers") || string("name").isNotBlank())) {
            "TwitterAPIs returned an unrecognized profile response"
        }
        return ProfileStats(
            fullName = string("name").ifBlank { handle },
            userName = handle,
            followersCount = count("followers_count", "followers"),
            followingsCount = count("following_count", "friends_count", "following"),
            statusesCount = count("tweet_count", "statuses_count", "tweets"),
            likeCount = count("favourites_count", "favorites_count", "like_count", "likes"),
            profileImage = highResolutionAvatar(string("profile_image_url", "profile_image_url_https", "profileImageUrl")),
            isVerified = boolean("is_blue_verified", "verified"),
            isPrivate = boolean("protected", "is_private"),
            followersKnown = known("followers_count", "followers"),
            followingKnown = known("following_count", "friends_count", "following"),
            postsKnown = known("tweet_count", "statuses_count", "tweets"),
            likesKnown = known("favourites_count", "favorites_count", "like_count", "likes"),
        )
    }

    fun fetchFollowers(username: String, cursor: String, apiKey: String): TopFollowersPage {
        require(apiKey.isNotBlank()) { "TwitterAPIs key is not configured" }
        val encodedUsername = encode(username.trim().trimStart('@'))
        val url = buildString {
            append(ENDPOINT).append("?username=").append(encodedUsername)
            if (cursor.isNotBlank()) append("&cursor=").append(encode(cursor))
        }
        val response = HttpTransport.get(
            url,
            headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
            userAgent = "Twidget (Android)",
        )
        val body = HttpTransport.requireSuccess(response, "TwitterAPIs")
        return parsePage(body)
    }

    /** Recent timeline reads are always bring-your-own-key; the app trial key is scan-only. */
    fun fetchTimelinePage(context: Context, username: String, cursor: String): TwitterApisTimelinePage {
        val apiKey = SecureCredentialStore.read(context, SecureCredentialStore.TWITTERAPIS_API_KEY)
        require(apiKey.isNotBlank()) { "TwitterAPIs key is not configured" }
        val url = buildString {
            append(TIMELINE_ENDPOINT)
                .append("?userName=")
                .append(encode(username.trim().trimStart('@')))
            if (cursor.isNotBlank()) append("&cursor=").append(encode(cursor))
        }
        val response = HttpTransport.get(
            url,
            headers = mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
            userAgent = "Twidget (Android)",
        )
        return parseTimelinePage(HttpTransport.requireSuccess(response, "TwitterAPIs"))
    }

    internal fun parseTimelinePage(body: String): TwitterApisTimelinePage {
        val root = JSONObject(body)
        val container = root.optJSONObject("data") ?: root
        val array = container.optJSONArray("tweets") ?: root.optJSONArray("tweets")
        val tweets = buildList {
            if (array != null) for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
        val nextCursor = sequenceOf(container, root)
            .map { it.optString("next_cursor").ifBlank { it.optString("nextCursor") } }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val hasMore = sequenceOf(container, root)
            .firstOrNull { it.has("has_more") || it.has("hasMore") }
            ?.let { it.optBoolean("has_more", it.optBoolean("hasMore")) }
            ?: nextCursor.isNotBlank()
        return TwitterApisTimelinePage(tweets, nextCursor, hasMore)
    }

    internal fun parsePage(body: String): TopFollowersPage {
        val root = JSONObject(body)
        val array = root.optJSONArray("users")
        val users = buildList {
            if (array != null) for (index in 0 until array.length()) {
                val user = array.optJSONObject(index) ?: continue
                val handle = user.optString("username").trim().trimStart('@')
                if (handle.isBlank()) continue
                add(TopFollower(
                    id = user.optString("id"),
                    username = handle,
                    name = user.optString("name").ifBlank { handle },
                    followers = user.optLong("followers_count").coerceAtLeast(0),
                    verified = user.optBoolean("is_blue_verified"),
                    avatarUrl = highResolutionAvatar(
                        sequenceOf(
                            "profile_image_url_https",
                            "profile_image_url",
                            "avatar_url",
                            "avatar",
                            "profile_image",
                        ).map(user::optString).firstOrNull { it.isNotBlank() }.orEmpty(),
                    ),
                ))
            }
        }
        return TopFollowersPage(users, root.optString("next_cursor"))
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    internal fun highResolutionAvatar(url: String): String = url.trim()
        .let { if (it.startsWith("//")) "https:$it" else it }
        .replace(Regex("^http://pbs\\.twimg\\.com/", RegexOption.IGNORE_CASE), "https://pbs.twimg.com/")
        .replace("_normal.", "_400x400.")
        .replace("name=normal", "name=400x400")
}
