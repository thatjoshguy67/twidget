package com.tjg.twidget.bridge

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only ring buffer of bridge traffic, viewable from the hidden debug
 * menu. Nothing is recorded until the debug menu has been unlocked, so regular
 * installs never persist request or response payloads.
 */
object BridgeLog {
    data class Entry(
        val timestamp: Long,
        val method: String,
        val url: String,
        val code: Int?,
        val requestBody: String?,
        val responseBody: String?,
        val durationMs: Long,
        val error: String?,
    )

    private const val PREFS = "twidget_bridge_log"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100
    private const val MAX_BODY_CHARS = 4000
    private val lock = Any()

    fun record(
        context: Context?,
        method: String,
        url: String,
        code: Int?,
        responseBody: String?,
        durationMs: Long,
        requestBody: String? = null,
        error: String? = null,
    ) {
        context ?: return
        if (!TwidgetStore.debugMenuUnlocked(context)) return
        synchronized(lock) {
            val entries = load(context).toMutableList()
            entries += Entry(
                timestamp = System.currentTimeMillis(),
                method = method,
                url = url,
                code = code,
                requestBody = requestBody?.take(MAX_BODY_CHARS),
                responseBody = responseBody?.take(MAX_BODY_CHARS),
                durationMs = durationMs,
                error = error,
            )
            save(context, entries.takeLast(MAX_ENTRIES))
        }
    }

    /** Recorded traffic, newest first. */
    fun entries(context: Context): List<Entry> = synchronized(lock) {
        load(context).asReversed()
    }

    fun clear(context: Context) = synchronized(lock) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }

    private fun load(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> fromJson(array.getJSONObject(index)) }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, entries: List<Entry>) {
        prefs(context).edit()
            .putString(KEY_ENTRIES, JSONArray(entries.map { toJson(it) }).toString())
            .apply()
    }

    private fun toJson(entry: Entry): JSONObject = JSONObject()
        .put("ts", entry.timestamp)
        .put("method", entry.method)
        .put("url", entry.url)
        .put("ms", entry.durationMs)
        .apply {
            entry.code?.let { put("code", it) }
            entry.requestBody?.let { put("req", it) }
            entry.responseBody?.let { put("res", it) }
            entry.error?.let { put("err", it) }
        }

    private fun fromJson(json: JSONObject): Entry = Entry(
        timestamp = json.optLong("ts"),
        method = json.optString("method"),
        url = json.optString("url"),
        code = if (json.has("code")) json.optInt("code") else null,
        requestBody = json.optString("req").takeIf { json.has("req") },
        responseBody = json.optString("res").takeIf { json.has("res") },
        durationMs = json.optLong("ms"),
        error = json.optString("err").takeIf { json.has("err") },
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
