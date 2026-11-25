package com.tjg.twidget.providers

import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.core.json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Fetches profile stats from the public FxTwitter API (api.fxtwitter.com).
 * No credentials or bridge required, and unlike Rettiwt it reports
 * verified/protected status directly. Provides no history array — daily
 * samples accumulate locally via TwidgetStore.saveStats.
 */
object FxTwitterClient {
    private const val BASE_URL = "https://api.fxtwitter.com"

    internal fun identity(username: String, userId: String = ""): String =
        userId.takeIf { it.matches(Regex("[0-9]+")) }?.let { "id:$it" }
            ?: username.trim().trimStart('@')

    internal fun profileUrl(username: String, userId: String = ""): String =
        "$BASE_URL/2/profile/${URLEncoder.encode(identity(username, userId), StandardCharsets.UTF_8.name())}"

    internal fun timelineUrl(username: String, userId: String, count: Int, cursor: String = "", withReplies: Boolean = false): String =
        buildString {
            append(profileUrl(username, userId)).append("/statuses?count=").append(count.coerceIn(1, 100))
            if (withReplies) append("&with_replies=true")
            if (cursor.isNotBlank()) append("&cursor=").append(URLEncoder.encode(cursor, StandardCharsets.UTF_8.name()))
        }

    fun fetchProfile(username: String, userId: String = ""): ProfileStats {
        if (userId.matches(Regex("[0-9]+"))) return fetchProfileById(userId)
        val json = JSONObject(read(profileUrl(username)))
        return NetworkResponseParsers.parseFxTwitterUser(json, username)
    }

    fun fetchProfileById(userId: String): ProfileStats {
        require(userId.matches(Regex("[0-9]+")))
        val profile = NetworkResponseParsers.parseFxTwitterUser(
            JSONObject(read(profileUrl("", userId))), "",
        )
        require(profile.userId == userId) { "X account identity could not be verified." }
        return profile
    }

    private fun read(url: String): String {
        val response = HttpTransport.get(url, userAgent = "Twidget (Android)")
        return HttpTransport.requireSuccess(response, "FxTwitter")
    }
}
