@file:Suppress("UseKtx") // This project intentionally excludes AndroidX core-ktx.

package com.tjg.twidget.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tjg.twidget.R
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.data.TwidgetStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

object BufferOAuth {
    private const val AUTH_URL = "https://auth.buffer.com/auth"
    private const val TOKEN_URL = "https://auth.buffer.com/token"
    private const val KEY_PENDING_STATE = "buffer_oauth_pending_state"
    private const val KEY_PENDING_VERIFIER = "buffer_oauth_pending_verifier"
    private const val SCOPES = "posts:write posts:read account:read offline_access"
    private val lock = Any()

    fun isConfigured(context: Context): Boolean = clientId(context).isNotBlank()

    fun isConnected(context: Context): Boolean =
        SecureCredentialStore.read(context, SecureCredentialStore.BUFFER_REFRESH_TOKEN).isNotBlank() ||
            SecureCredentialStore.read(context, SecureCredentialStore.BUFFER_ACCESS_TOKEN).isNotBlank()

    fun authorizationIntent(context: Context): Intent {
        val clientId = clientId(context)
        require(clientId.isNotBlank()) { "Buffer OAuth client ID is not configured" }
        val verifier = randomUrlToken(48)
        val state = randomUrlToken(32)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        )
        prefs(context).edit()
            .putString(KEY_PENDING_STATE, state)
            .putString(KEY_PENDING_VERIFIER, verifier)
            .apply()
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri(context))
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "consent")
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    fun exchangeCallback(context: Context, callback: Uri): Result<Unit> = runCatching {
        callback.getQueryParameter("error")?.let { error(it) }
        val expectedState = prefs(context).getString(KEY_PENDING_STATE, null)
        val verifier = prefs(context).getString(KEY_PENDING_VERIFIER, null)
        val receivedState = callback.getQueryParameter("state")
        prefs(context).edit().remove(KEY_PENDING_STATE).remove(KEY_PENDING_VERIFIER).apply()
        require(!expectedState.isNullOrBlank() && expectedState == receivedState) {
            "Buffer sign-in could not be verified. Please try again."
        }
        val code = callback.getQueryParameter("code")?.takeIf(String::isNotBlank)
            ?: error("Buffer did not return an authorization code")
        val body = form(
            "client_id" to clientId(context),
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri(context),
            "code_verifier" to requireNotNull(verifier),
        )
        saveTokenResponse(context, requestToken(body))
    }

    fun accessToken(context: Context): String = synchronized(lock) {
        val access = SecureCredentialStore.read(context, SecureCredentialStore.BUFFER_ACCESS_TOKEN)
        val expiry = SecureCredentialStore.read(context, SecureCredentialStore.BUFFER_TOKEN_EXPIRY).toLongOrNull() ?: 0L
        if (access.isNotBlank() && expiry > System.currentTimeMillis() + 60_000L) return@synchronized access
        val refresh = SecureCredentialStore.read(context, SecureCredentialStore.BUFFER_REFRESH_TOKEN)
        if (refresh.isBlank()) {
            if (access.isNotBlank() && expiry == 0L) return@synchronized access
            error("Connect Buffer in Scheduling settings")
        }
        val body = form(
            "client_id" to clientId(context),
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
        )
        saveTokenResponse(context, requestToken(body), refresh)
        SecureCredentialStore.read(context, SecureCredentialStore.BUFFER_ACCESS_TOKEN)
            .takeIf(String::isNotBlank) ?: error("Buffer did not return an access token")
    }

    fun disconnect(context: Context) {
        SecureCredentialStore.write(
            context,
            mapOf(
                SecureCredentialStore.BUFFER_ACCESS_TOKEN to "",
                SecureCredentialStore.BUFFER_REFRESH_TOKEN to "",
                SecureCredentialStore.BUFFER_TOKEN_EXPIRY to "",
            ),
        )
        prefs(context).edit().remove(KEY_PENDING_STATE).remove(KEY_PENDING_VERIFIER).apply()
    }

    private fun requestToken(body: String): JSONObject {
        val response = HttpTransport.post(
            TOKEN_URL,
            body,
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
        )
        val json = runCatching { JSONObject(response.body) }.getOrElse { JSONObject() }
        if (response.code !in 200..299) {
            error(json.optString("error_description").ifBlank { json.optString("error").ifBlank { "Buffer sign-in failed" } })
        }
        return json
    }

    private fun saveTokenResponse(context: Context, json: JSONObject, previousRefresh: String = "") {
        val access = json.optString("access_token").takeIf(String::isNotBlank)
            ?: error("Buffer did not return an access token")
        val refresh = json.optString("refresh_token").takeIf(String::isNotBlank) ?: previousRefresh
        val expiresIn = json.optLong("expires_in", 3600L).coerceAtLeast(60L)
        SecureCredentialStore.write(
            context,
            mapOf(
                SecureCredentialStore.BUFFER_ACCESS_TOKEN to access,
                SecureCredentialStore.BUFFER_REFRESH_TOKEN to refresh,
                SecureCredentialStore.BUFFER_TOKEN_EXPIRY to (System.currentTimeMillis() + expiresIn * 1000L).toString(),
            ),
        )
    }

    private fun form(vararg fields: Pair<String, String>): String = fields.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun clientId(context: Context): String = context.getString(R.string.buffer_oauth_client_id).trim()
    private fun redirectUri(context: Context): String = context.getString(R.string.buffer_oauth_redirect_uri)
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
    private fun randomUrlToken(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).let(::base64Url)
    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
