package com.tjg.twidget

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Talks to the official X API directly from the device with the user's own
 * credentials, so accurate stats never depend on the shared Twidget bridge.
 * Accepts either a ready-made bearer token, or an API key/secret pair which
 * is exchanged for an app-only bearer token (cached until it stops working).
 */
object XApiClient {
    private const val TOKEN_URL = "https://api.x.com/oauth2/token"
    private const val USER_FIELDS = "public_metrics,profile_image_url,verified,verified_type,protected"

    fun hasCredentials(settings: TwidgetSettings): Boolean =
        settings.xApiToken.isNotBlank() ||
            (settings.xApiKey.isNotBlank() && settings.xApiSecret.isNotBlank())

    fun fetchProfile(context: Context, username: String): ProfileStats {
        val settings = TwidgetStore.settings(context)
        if (!hasCredentials(settings)) {
            throw IllegalStateException("X API credentials are not configured")
        }
        if (settings.xApiToken.isNotBlank()) {
            return fetchUser(username, settings.xApiToken)
        }

        val cached = TwidgetStore.cachedXApiBearer(context)
        if (cached.isNotBlank()) {
            try {
                return fetchUser(username, cached)
            } catch (error: HttpError) {
                if (error.code != 401) throw error
            }
        }
        val fresh = exchangeBearer(settings.xApiKey, settings.xApiSecret)
        TwidgetStore.saveXApiBearer(context, fresh)
        return fetchUser(username, fresh)
    }

    /** Exchanges an API key/secret for an app-only bearer token. Throws on bad credentials. */
    fun exchangeBearer(apiKey: String, apiSecret: String): String {
        val encode = { value: String -> URLEncoder.encode(value, StandardCharsets.UTF_8.name()) }
        val basic = Base64.encodeToString(
            "${encode(apiKey)}:${encode(apiSecret)}".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        val body = post(
            TOKEN_URL,
            "grant_type=client_credentials",
            mapOf(
                "Authorization" to "Basic $basic",
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
            ),
        )
        val token = JSONObject(body).optString("access_token")
        if (token.isBlank()) throw IllegalStateException("X API token exchange returned no access token")
        return token
    }

    private fun fetchUser(username: String, bearer: String): ProfileStats {
        val encoded = URLEncoder.encode(username.trim().trimStart('@'), StandardCharsets.UTF_8.name())
        val body = get(
            "https://api.x.com/2/users/by/username/$encoded?user.fields=$USER_FIELDS",
            mapOf("Authorization" to "Bearer ${bearer.trim().removePrefix("Bearer ")}"),
        )
        val json = JSONObject(body)
        val user = json.optJSONObject("data")
            ?: throw IllegalStateException(
                json.optJSONArray("errors")?.optJSONObject(0)?.optString("detail")
                    ?.takeIf { it.isNotBlank() } ?: "X API returned no user for @$username"
            )
        val metrics = user.optJSONObject("public_metrics") ?: JSONObject()
        return ProfileStats(
            fullName = user.optString("name").ifBlank { username },
            userName = user.optString("username").ifBlank { username }.trimStart('@'),
            followersCount = metrics.optLong("followers_count"),
            followingsCount = metrics.optLong("following_count"),
            statusesCount = metrics.optLong("tweet_count"),
            likeCount = metrics.optLong("like_count"),
            profileImage = highResolutionProfileImageUrl(user.optString("profile_image_url")),
            isVerified = user.optBoolean("verified", false) ||
                user.optString("verified_type").let { it.isNotBlank() && it != "none" },
            isPrivate = user.optBoolean("protected", false),
            syncedAt = System.currentTimeMillis(),
        )
    }

    private fun get(url: String, headers: Map<String, String>): String =
        request("GET", url, null, headers)

    private fun post(url: String, body: String, headers: Map<String, String>): String =
        request("POST", url, body, headers)

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        if (code !in 200..299) throw HttpError(code, "X API HTTP $code: ${text.take(300)}")
        return text
    }

    private fun highResolutionProfileImageUrl(url: String): String =
        url.trim()
            .replace(Regex("_normal(?=\\.[A-Za-z0-9]+(?:\\?|$))"), "_400x400")
            .replace(Regex("([?&]name=)normal(?=(&|$))"), "${'$'}1400x400")

    class HttpError(val code: Int, message: String) : IllegalStateException(message)
}
