package com.tjg.twidget.providers

import android.content.Context
import android.util.Base64
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollower
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Talks to the official X API directly from the device with the user's own
 * credentials, so official profile fields never depend on the shared Twidget bridge.
 * Accepts either a ready-made bearer token, or an API key/secret pair which
 * is exchanged for an app-only bearer token (cached until it stops working).
 */
object XApiClient {
    const val DEVELOPER_PORTAL_URL = "https://developer.x.com/en/portal/dashboard"
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
            } catch (error: HttpTransport.HttpException) {
                if (error.code != 401) throw error
            }
        }
        val fresh = exchangeBearer(settings.xApiKey, settings.xApiSecret)
        TwidgetStore.saveXApiBearer(context, fresh)
        return fetchUser(username, fresh)
    }

    fun fetchProfileWithBearer(username: String, bearer: String): ProfileStats =
        fetchUser(username, bearer)

    data class FollowersAccess(val bearer: String, val userId: String)

    fun followersAccess(context: Context, username: String): FollowersAccess? {
        val settings = TwidgetStore.settings(context)
        if (!hasCredentials(settings)) return null
        val bearer = resolveBearer(context, settings)
        val encoded = URLEncoder.encode(username.trim().trimStart('@'), StandardCharsets.UTF_8.name())
        val root = JSONObject(get(
            "https://api.x.com/2/users/by/username/$encoded",
            mapOf("Authorization" to "Bearer $bearer"),
        ))
        val userId = root.optJSONObject("data")?.optString("id").orEmpty()
        return userId.takeIf(String::isNotBlank)?.let { FollowersAccess(bearer, it) }
    }

    fun fetchFollowers(access: FollowersAccess, cursor: String): TopFollowersPage {
        val query = buildString {
            append("max_results=1000")
            append("&user.fields=id,name,username,public_metrics,profile_image_url,verified,protected")
            if (cursor.isNotBlank()) {
                append("&pagination_token=")
                append(URLEncoder.encode(cursor, StandardCharsets.UTF_8.name()))
            }
        }
        val root = JSONObject(get(
            "https://api.x.com/2/users/${access.userId}/followers?$query",
            mapOf("Authorization" to "Bearer ${access.bearer}"),
        ))
        val data = root.optJSONArray("data")
        val users = buildList {
            if (data != null) for (index in 0 until data.length()) {
                val user = data.optJSONObject(index) ?: continue
                add(TopFollower(
                    id = user.optString("id"),
                    username = user.optString("username"),
                    name = user.optString("name"),
                    followers = user.optJSONObject("public_metrics")?.optLong("followers_count") ?: 0L,
                    verified = user.optBoolean("verified"),
                    avatarUrl = user.optString("profile_image_url"),
                ))
            }
        }
        return TopFollowersPage(
            users = users,
            nextCursor = root.optJSONObject("meta")?.optString("next_token").orEmpty(),
        )
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

    private fun resolveBearer(context: Context, settings: TwidgetSettings): String {
        settings.xApiToken.trim().removePrefix("Bearer ").takeIf(String::isNotBlank)?.let { return it }
        TwidgetStore.cachedXApiBearer(context).trim().removePrefix("Bearer ")
            .takeIf(String::isNotBlank)?.let { return it }
        return exchangeBearer(settings.xApiKey, settings.xApiSecret).also {
            TwidgetStore.saveXApiBearer(context, it)
        }
    }

    private fun fetchUser(username: String, bearer: String): ProfileStats {
        val encoded = URLEncoder.encode(username.trim().trimStart('@'), StandardCharsets.UTF_8.name())
        val body = get(
            "https://api.x.com/2/users/by/username/$encoded?user.fields=$USER_FIELDS",
            mapOf("Authorization" to "Bearer ${bearer.trim().removePrefix("Bearer ")}"),
        )
        return NetworkResponseParsers.parseXApiUser(JSONObject(body), username)
    }

    private fun get(url: String, headers: Map<String, String>): String =
        request("GET", url, null, headers)

    private fun post(url: String, body: String, headers: Map<String, String>): String =
        request("POST", url, body, headers)

    private fun request(method: String, url: String, body: String?, headers: Map<String, String>): String {
        val response = if (method == "GET") {
            HttpTransport.get(url, headers)
        } else {
            HttpTransport.post(url, body.orEmpty(), headers)
        }
        return HttpTransport.requireSuccess(response, "X API")
    }
}
