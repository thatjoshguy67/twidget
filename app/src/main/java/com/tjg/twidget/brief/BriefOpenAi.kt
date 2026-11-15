package com.tjg.twidget.brief

import android.content.Context
import android.util.Base64
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.SecureCredentialStore
import org.json.JSONArray
import org.json.JSONObject

/** Both connection methods use the public Responses API. Credentials never go through the bridge. */
internal object BriefOpenAi {
    const val KEY = "openai_api_key"
    const val ACCOUNTS = "chatgpt_accounts"
    fun prefs(context: Context) = context.getSharedPreferences("brief_openai", Context.MODE_PRIVATE)
    fun method(context: Context) = prefs(context).getString("method", "gemini") ?: "gemini"
    fun configured(context: Context): String = when (method(context)) {
        "key" -> SecureCredentialStore.read(context, KEY)
        "chatgpt" -> active(context)?.optString("refresh_token").orEmpty()
        else -> ""
    }
    fun accounts(context: Context): JSONArray = SecureCredentialStore.read(context, ACCOUNTS).let {
        if (it.isBlank()) JSONArray() else JSONArray(it)
    }
    fun active(context: Context): JSONObject? {
        val id = prefs(context).getString("account", null)
        val all = accounts(context)
        return (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("client_id") == id }
    }
    @Synchronized fun save(context: Context, account: JSONObject) {
        val all = accounts(context)
        val updated = JSONArray()
        var replaced = false
        for (i in 0 until all.length()) {
            val old = all.getJSONObject(i)
            if (old.getString("client_id") == account.getString("client_id")) {
                check(old.getString("subject") == account.getString("subject"))
                updated.put(account); replaced = true
            } else updated.put(old)
        }
        if (!replaced) updated.put(account)
        SecureCredentialStore.write(context, mapOf(ACCOUNTS to updated.toString()))
    }
    @Synchronized fun signOut(context: Context): Boolean {
        val account = active(context) ?: return true
        var revoked = false
        try {
            val discovery = JSONObject(HttpTransport.requireSuccess(HttpTransport.get("https://auth.openai.com/.well-known/openid-configuration"), "OpenAI discovery"))
            val endpoint = discovery.getString("revocation_endpoint")
            check(endpoint.startsWith("https://auth.openai.com/"))
            repeat(2) { attempt ->
                if (!revoked) {
                    val response = HttpTransport.post(endpoint, form(mapOf("token" to account.getString("refresh_token"), "token_type_hint" to "refresh_token", "client_id" to account.getString("client_id"))), mapOf("Content-Type" to "application/x-www-form-urlencoded"))
                    revoked = response.code == 200
                    if (!revoked && response.code >= 500 && attempt == 0) Thread.sleep(500)
                }
            }
        } catch (_: Exception) { /* Clear locally even if remote revocation is unavailable. */ }
        account.remove("access_token"); account.remove("refresh_token"); account.remove("id_token")
        save(context, account)
        return revoked
    }
    fun form(values: Map<String, String>) = values.entries.joinToString("&") {
        java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
    }
    fun token(values: Map<String, String>): JSONObject = BriefOAuthNetwork.resolveAndRequest { JSONObject(HttpTransport.requireSuccess(
        HttpTransport.post("https://auth.openai.com/api/accounts/oauth/token", form(values),
            mapOf("Content-Type" to "application/x-www-form-urlencoded")), "OpenAI authentication")) }
    @Synchronized private fun bearer(context: Context): String {
        if (method(context) == "key") return SecureCredentialStore.read(context, KEY)
        val account = active(context) ?: throw BriefReviewException(BriefReviewFailure.MISSING_KEY)
        if (account.optLong("expires_at") < System.currentTimeMillis() + 60_000) {
            val fresh = token(mapOf("grant_type" to "refresh_token", "client_id" to account.getString("client_id"),
                "refresh_token" to account.getString("refresh_token"), "resource" to "https://api.openai.com/v1"))
            check(fresh.optString("access_token").isNotBlank())
            if (fresh.has("scope")) check("chatgpt.tokens.use.direct" in fresh.getString("scope").split(" "))
            account.put("access_token", fresh.getString("access_token"))
                .put("expires_at", System.currentTimeMillis() + fresh.getLong("expires_in") * 1000)
            if (fresh.optString("refresh_token").isNotBlank()) account.put("refresh_token", fresh.getString("refresh_token"))
            if (fresh.has("scope")) account.put("scope", fresh.getString("scope"))
            save(context, account)
        }
        return account.getString("access_token")
    }
    fun models(context: Context): List<Pair<String, String>> {
        val root = JSONObject(HttpTransport.requireSuccess(HttpTransport.get("https://api.openai.com/v1/models",
            mapOf("Authorization" to "Bearer ${bearer(context)}")), "OpenAI"))
        val items = root.optJSONArray("models") ?: root.getJSONArray("data")
        return (0 until items.length()).map { items.getJSONObject(it) }.filter {
            !it.has("visibility") || it.optString("visibility") == "list"
        }.map { (it.optString("slug").ifBlank { it.getString("id") }) to it.optString("display_name").ifBlank { it.optString("id") } }
    }
    fun modelKey(context: Context, review: Boolean): String = BriefModelPolicy.modelKey(
        method(context), prefs(context).getString("account", "").orEmpty(), review)
    fun selectedModel(context: Context, review: Boolean): String =
        if (com.tjg.twidget.data.TwidgetStore.debugMenuUnlocked(context))
            prefs(context).getString(modelKey(context, review), null) ?: BriefModelPolicy.openAiModel(review)
        else BriefModelPolicy.openAiModel(review)
    fun request(context: Context, prompt: String, instructions: String, images: List<BriefCoachImage> = emptyList(), review: Boolean = false): String {
        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt))
        images.forEach { item ->
            content.put(JSONObject().put("type", "input_text").put("text", "Attachment ${item.id}"))
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                check(item.bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, output)); output.toByteArray()
            }
            content.put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        val model = selectedModel(context, review)
        val body = JSONObject().put("model", model).put("instructions", instructions)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("store", false).put("stream", true)
        if (model.startsWith("gpt-6") || model.startsWith("gpt-5")) {
            body.put("reasoning", JSONObject().put("effort", BriefModelPolicy.reasoning(review)))
                .put("text", JSONObject().put("verbosity", "low"))
        }
        val response = HttpTransport.post("https://api.openai.com/v1/responses", body.toString(),
            mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${bearer(context)}"), readTimeoutMs = 75_000)
        return parseStream(HttpTransport.requireSuccess(response, "OpenAI"))
    }
    internal fun parseStream(raw: String): String {
        val text = StringBuilder()
        var completed = false
        for (event in raw.replace("\r\n", "\n").split("\n\n")) {
            val data = event.lineSequence().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").trimStart() }
            if (data.isBlank() || data == "[DONE]") continue
            val value = JSONObject(data)
            when (value.optString("type")) {
                "response.output_text.delta" -> text.append(value.getString("delta"))
                "response.completed" -> completed = true
                "response.failed", "response.incomplete", "error" -> {
                    val code = value.optJSONObject("response")?.optJSONObject("error")?.optString("code") ?: value.optString("code")
                    throw BriefReviewException(when {
                        code.contains("usage_limit") || code.contains("usage_unavailable") || code.contains("rate_limit") -> BriefReviewFailure.QUOTA
                        code.contains("invalid_user") || code.contains("authentication") -> BriefReviewFailure.AUTHENTICATION
                        value.optString("type") == "response.incomplete" -> BriefReviewFailure.OUTPUT_LIMIT
                        else -> BriefReviewFailure.SERVICE
                    })
                }
            }
        }
        if (!completed || text.isBlank()) throw BriefReviewException(BriefReviewFailure.INVALID_RESPONSE)
        return text.toString()
    }
}
