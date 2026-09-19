package com.tjg.twidget.social

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.SecureCredentialStore
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/** Tokens and the device's pending-login proof are encrypted with the existing Android Keystore. */
object SocialConnections {
    private const val BRIDGE = "https://twidget-bridge-production.up.railway.app"
    private const val PENDING = "social_oauth_pending"
    private const val MAX_AGE = 10 * 60 * 1000L
    private fun sessionKey(id: String) = "social_${id}_session"
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun token(context: Context, accountId: String): String = session(context, accountId)?.optString("accessToken").orEmpty()
    @Synchronized fun refreshableToken(context: Context, account: PlatformAccount): String {
        val raw = rawSession(context, account.id)
        if (account.platform == SocialPlatform.GITHUB && raw != null && !raw.isNull("expiresAt") &&
            raw.optLong("expiresAt", Long.MAX_VALUE) - System.currentTimeMillis() < 2 * 60 * 1000L) {
            val refresh = raw.optString("refreshToken")
            if (refresh.isBlank() || raw.optLong("refreshExpiresAt") <= System.currentTimeMillis()) return ""
            val response = HttpTransport.post("$BRIDGE/oauth/github/refresh", JSONObject().put("refreshToken", refresh).toString(), mapOf("Content-Type" to "application/json"))
            if (response.code != 200) return ""
            val rotated = JSONObject(response.body)
            saveSession(context, account.id, rotated)
            return rotated.getString("accessToken")
        }
        val current = session(context, account.id)
        if (account.platform == SocialPlatform.YOUTUBE && current == null) {
            val authorization = com.google.android.gms.tasks.Tasks.await(
                com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context)
                    .authorize(com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
                        .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope("https://www.googleapis.com/auth/youtube.readonly"))).build()),
                15, java.util.concurrent.TimeUnit.SECONDS)
            if (authorization.hasResolution()) return ""
            // The refresh caller verifies the channel's remote ID before persisting or applying data.
            return authorization.accessToken.orEmpty()
        }
        val token = current?.optString("accessToken").orEmpty()
        if (account.platform == SocialPlatform.INSTAGRAM && token.isNotBlank() && current != null &&
            current.optLong("expiresAt", Long.MAX_VALUE) - System.currentTimeMillis() < 7 * 24 * 60 * 60 * 1000L) {
            val response = HttpTransport.get("https://graph.instagram.com/refresh_access_token?grant_type=ig_refresh_token&access_token=" + java.net.URLEncoder.encode(token, "UTF-8"))
            if (response.code == 200) {
                val refreshed = JSONObject(response.body)
                val replacement = refreshed.getString("access_token")
                val seconds = refreshed.getLong("expires_in")
                require(replacement.isNotBlank() && seconds in 1..(90 * 24 * 60 * 60L))
                save(context, account.id, replacement, System.currentTimeMillis() + seconds * 1000)
                return replacement
            }
        }
        return token
    }

    private fun rawSession(context: Context, id: String): JSONObject? = runCatching {
        JSONObject(SecureCredentialStore.read(context, sessionKey(id)))
    }.getOrNull()
    private fun session(context: Context, id: String): JSONObject? = rawSession(context, id)?.takeIf {
            !it.has("expiresAt") || it.isNull("expiresAt") || it.getLong("expiresAt") > System.currentTimeMillis()
        }
    fun saveSession(context: Context, accountId: String, tokens: JSONObject) {
        require(tokens.getString("accessToken").isNotBlank())
        SecureCredentialStore.write(context, mapOf(sessionKey(accountId) to tokens.toString()))
    }
    fun save(context: Context, accountId: String, token: String, expiresAt: Long? = null) {
        require(token.isNotBlank())
        SecureCredentialStore.write(context, mapOf(sessionKey(accountId) to JSONObject().put("accessToken", token)
            .put("expiresAt", expiresAt ?: JSONObject.NULL).toString()))
    }
    fun disconnect(context: Context, accountId: String) = SecureCredentialStore.clear(context, sessionKey(accountId))

    fun start(context: Context, platform: SocialPlatform, addMode: Boolean = false, upgrade: Boolean = false): Uri {
        require(platform in setOf(SocialPlatform.GITHUB, SocialPlatform.INSTAGRAM))
        val verifier = encode(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val response = HttpTransport.post("$BRIDGE/oauth/${platform.storageId}/start", JSONObject().put("challenge", challenge).toString(),
            mapOf("Content-Type" to "application/json"))
        check(response.code == 200) { "Connection unavailable" }
        val body = JSONObject(response.body)
        val uri = Uri.parse(body.getString("authorizationUrl"))
        require(uri.scheme == "https" && uri.host == if (platform == SocialPlatform.GITHUB) "github.com" else "www.instagram.com")
        val state = body.getString("state")
        require(state.matches(Regex("[A-Za-z0-9_-]{43}")))
        SecureCredentialStore.write(context, mapOf(PENDING to JSONObject().put("provider", platform.storageId)
            .put("state", state).put("verifier", verifier).put("startedAt", System.currentTimeMillis()).put("flowAdd", addMode).put("flowUpgrade", upgrade).toString()))
        return uri
    }

    fun redeem(context: Context, uri: Uri): Pair<SocialPlatform, JSONObject> {
        require(validCallbackAddress(uri))
        val pending = JSONObject(SecureCredentialStore.read(context, PENDING))
        val platform = SocialPlatform.fromStorageId(pending.getString("provider"))
        require(uri.path == "/${platform.storageId}" && uri.getQueryParameters("state").size == 1 &&
            uri.getQueryParameter("state") == pending.getString("state"))
        require(System.currentTimeMillis() - pending.getLong("startedAt") in 0..MAX_AGE)
        // Consume locally even if cancelled or the bridge ticket has already expired.
        SecureCredentialStore.clear(context, PENDING)
        require(uri.getQueryParameter("error") == null) { "Connection cancelled" }
        val ticket = requireNotNull(uri.getQueryParameter("ticket"))
        require(ticket.matches(Regex("[A-Za-z0-9_-]{43}")) && uri.getQueryParameters("ticket").size == 1)
        val response = HttpTransport.post("$BRIDGE/oauth/${platform.storageId}/redeem", JSONObject()
            .put("ticket", ticket).put("verifier", pending.getString("verifier")).toString(), mapOf("Content-Type" to "application/json"))
        check(response.code == 200) { "Connection expired" }
        val tokens = JSONObject(response.body)
        require(tokens.getString("accessToken").isNotBlank())
        tokens.put("flowAdd", pending.optBoolean("flowAdd")).put("flowUpgrade", pending.optBoolean("flowUpgrade"))
        return platform to tokens
    }

    internal fun validCallbackAddress(uri: Uri): Boolean {
        // Browsers can inherit Instagram's #_ suffix through the bridge's HTTP redirect.
        // It carries no credentials; state, expiry and the device proof are still checked above.
        return uri.scheme == "twidget" && uri.authority == "oauth" &&
            (uri.fragment == null || (uri.path == "/instagram" && uri.encodedFragment == "_"))
    }
}
