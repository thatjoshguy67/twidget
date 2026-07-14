package com.tjg.twidget

import org.json.JSONObject
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
        return NetworkResponseParsers.parseFxTwitterUser(json, username)
    }

    private fun read(url: String): String {
        val response = HttpTransport.get(url, userAgent = "Twidget (Android)")
        return HttpTransport.requireSuccess(response, "FxTwitter")
    }
}
