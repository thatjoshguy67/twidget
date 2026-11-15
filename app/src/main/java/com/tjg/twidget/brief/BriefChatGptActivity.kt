package com.tjg.twidget.brief

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.tjg.twidget.core.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** Public-client OAuth uses the system browser, PKCE, state, nonce and a loopback-only listener. */
class BriefChatGptActivity : FragmentActivity() {
    companion object { @Volatile var awaitingReturn = false; private set }
    private var foregroundReady: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override fun onResume() {
        super.onResume()
        foregroundReady?.complete(Unit)
    }
    private var listener: ServerSocket? = null
    private var stage = "prepare"
    private var returnSocket: java.net.Socket? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        signIn()
    }
    override fun onDestroy() { listener?.close(); returnSocket?.close(); super.onDestroy() }
    private fun random(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let(::encode)
    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun signIn() = lifecycleScope.launch {
        try {
            val account = withContext(Dispatchers.IO) {
                val prefs = BriefOpenAi.prefs(this@BriefChatGptActivity)
                val host = prefs.getString("host", null) ?: "urn:uuid:${java.util.UUID.randomUUID()}".also { check(prefs.edit().putString("host", it).commit()) }
                val previous = if (intent.getBooleanExtra("new_account", false)) null else BriefOpenAi.active(this@BriefChatGptActivity)
                val state = random(); val nonce = random(); val verifier = random()
                val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                listener = server; server.soTimeout = 180_000
                val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
                val params = linkedMapOf("client_id" to (previous?.getString("client_id") ?: "dynamic_agent_client"),
                    "ext_agent_host_id" to host, "response_type" to "code", "redirect_uri" to redirect,
                    "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                    "resource" to "https://api.openai.com/v1", "state" to state, "nonce" to nonce,
                    "code_challenge_method" to "S256", "code_challenge" to encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
                if (previous == null) params["agent_name_hint"] = "Twidget"
                withContext(Dispatchers.Main) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://auth.openai.com/api/accounts/authorize?${BriefOpenAi.form(params)}"))) }
                stage = "browser_callback"
                val deadline = System.currentTimeMillis() + 180_000
                var callback: Uri? = null
                server.use {
                    while (callback == null && System.currentTimeMillis() < deadline) {
                        server.accept().let { socket ->
                            socket.soTimeout = 3000
                            val line = socket.getInputStream().bufferedReader().readLine().orEmpty().take(8192)
                            val target = line.split(' ').getOrNull(1).orEmpty()
                            val uri = Uri.parse("http://127.0.0.1$target")
                            val valid = line.startsWith("GET ") && uri.path == "/auth/callback" && uri.getQueryParameter("state") == state
                            if (valid) {
                                callback = uri
                                returnSocket = socket
                            } else {
                                socket.close()
                            }

                        }
                    }
                }
                listener = null
                stage = "callback_validation"
                val uri = checkNotNull(callback)
                check(uri.getQueryParameter("error") == null)
                val client = uri.getQueryParameter("client_id") ?: previous?.getString("client_id")
                check(!client.isNullOrBlank() && client != "dynamic_agent_client")
                if (previous != null) check(client == previous.getString("client_id"))
                // The browser callback is local, but the following requests need foreground
                // network access on devices that restrict background apps.
                withContext(Dispatchers.Main) {
                    foregroundReady = kotlinx.coroutines.CompletableDeferred()
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) foregroundReady?.complete(Unit)
                    awaitingReturn = true
                }
                respondToBrowser(false, pending = true)
                stage = "return_to_app"
                kotlinx.coroutines.withTimeout(60_000L) { foregroundReady!!.await() }
                awaitingReturn = false
                stage = "token_exchange"
                val tokens = BriefOpenAi.token(mapOf("grant_type" to "authorization_code", "client_id" to client,
                    "code" to checkNotNull(uri.getQueryParameter("code")), "code_verifier" to verifier,
                    "redirect_uri" to redirect, "resource" to "https://api.openai.com/v1"))
                stage = "plan_permission"
                check("chatgpt.tokens.use.direct" in tokens.getString("scope").split(' '))
                stage = "identity_validation"
                val identity = validateIdentity(tokens.getString("id_token"), client, nonce)
                if (previous != null) check(identity.getString("sub") == previous.getString("subject"))
                check(tokens.getString("access_token").isNotBlank() && tokens.getString("refresh_token").isNotBlank())
                tokens.put("client_id", client).put("subject", identity.getString("sub"))
                    .put("email", identity.optString("email")).put("expires_at", System.currentTimeMillis() + tokens.getLong("expires_in") * 1000)
            }
            stage = "credential_storage"
            BriefOpenAi.save(this@BriefChatGptActivity, account)
            BriefOpenAi.prefs(this@BriefChatGptActivity).edit().putString("account", account.getString("client_id")).putString("method", "chatgpt").remove("last_sign_in_error").apply()
            BriefStore.resetAi(this@BriefChatGptActivity, com.tjg.twidget.data.TwidgetStore.settings(this@BriefChatGptActivity).username)
            respondToBrowser(true)
            startActivity(Intent(this@BriefChatGptActivity, com.tjg.twidget.settings.BriefSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            respondToBrowser(false)
            val http = (error as? HttpTransport.HttpException)?.code
            val detail = "$stage · ${error.javaClass.simpleName}" + (http?.let { " · HTTP $it" } ?: "")
            android.util.Log.w("BriefOAuth", detail)
            BriefOpenAi.prefs(this@BriefChatGptActivity).edit().putString("last_sign_in_error", detail).apply()
            val message = if (error is java.net.UnknownHostException)
                "Could not reach OpenAI. Check your connection and try signing in again."
            else "ChatGPT sign-in failed at $stage. Please try again."
            Toast.makeText(this@BriefChatGptActivity, message, Toast.LENGTH_LONG).show()
            startActivity(Intent(this@BriefChatGptActivity, com.tjg.twidget.settings.BriefSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } finally { listener?.close(); listener = null; returnSocket?.close(); returnSocket = null; awaitingReturn = false; finish() }
    }
    private suspend fun respondToBrowser(success: Boolean, pending: Boolean = false) = withContext(Dispatchers.IO) {
        val socket = returnSocket ?: return@withContext
        try {
            val response = BriefOAuthReturnPage.html(success, packageName, pending).toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${response.size}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray())
                write(response); flush()
            }
        } catch (_: java.io.IOException) { /* Browser may already have closed the tab. */ }
        finally { socket.close(); returnSocket = null }
    }
    private fun validateIdentity(token: String, client: String, nonce: String): JSONObject {
        val parts = token.split('.'); check(parts.size == 3)
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        check(header.getString("alg") == "RS256")
        stage = "openid_discovery"
        val config = JSONObject(HttpTransport.requireSuccess(HttpTransport.get("https://auth.openai.com/.well-known/openid-configuration"), "OpenAI discovery"))
        val jwks = config.getString("jwks_uri")
        check(jwks.startsWith("https://auth.openai.com/"))
        stage = "signing_keys"
        val keys = JSONObject(HttpTransport.requireSuccess(HttpTransport.get(jwks), "OpenAI keys")).getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.first { it.getString("kid") == header.getString("kid") && it.getString("kty") == "RSA" }
        stage = "token_signature"
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, decode(key.getString("n"))), BigInteger(1, decode(key.getString("e")))))
        check(Signature.getInstance("SHA256withRSA").run { initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(decode(parts[2])) })
        stage = "identity_claims"
        val claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        check(claims.getString("iss") == "https://auth.openai.com" && claims.getString("iss") == config.getString("issuer"))
        val audiences = claims.optJSONArray("aud")?.let { values -> (0 until values.length()).map { values.getString(it) } } ?: listOf(claims.getString("aud"))
        check(client in audiences && (audiences.size == 1 || claims.optString("azp") == client))
        check(claims.getLong("exp") > System.currentTimeMillis() / 1000 && claims.getString("nonce") == nonce && claims.getString("sub").isNotBlank())
        return claims
    }
}
