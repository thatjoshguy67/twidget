package com.tjg.twidget

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Fetches profile stats from the public FxTwitter API (api.fxtwitter.com).
 * No credentials or bridge required, and unlike Rettiwt it reports
 * verified/protected status directly. Provides no history array — daily
 * samples accumulate locally via TwidgetStore.saveStats.
 */
object FxTwitterClient {
    private const val BASE_URL = "https://api.fxtwitter.com"

    fun fetchProfile(username: String): ProfileStats {
        val encoded = URLEncoder.encode(username.trim().trimStart('@'), StandardCharsets.UTF_8.name())
        val json = JSONObject(read("$BASE_URL/$encoded"))
        val user = json.optJSONObject("user")
            ?: throw IllegalStateException(
                "FxTwitter returned no user for @$username" +
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
        )
    }

    private fun read(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Twidget (Android)")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("FxTwitter HTTP $code: ${body.take(300)}")
        return body
    }
}
