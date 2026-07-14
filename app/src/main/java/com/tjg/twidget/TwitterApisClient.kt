package com.tjg.twidget

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class TopFollowersPage(val users: List<TopFollower>, val nextCursor: String)

object TwitterApisClient {
    const val WEBSITE_URL = "https://twitterapis.com"
    private const val ENDPOINT = "https://api.twitterapis.com/twitter/user/followers_v2"

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
                    avatarUrl = highResolutionAvatar(user.optString("profile_image_url")),
                ))
            }
        }
        return TopFollowersPage(users, root.optString("next_cursor"))
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun highResolutionAvatar(url: String): String = url
        .replace("_normal.", "_400x400.")
        .replace("name=normal", "name=400x400")
}
