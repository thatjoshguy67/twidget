package com.tjg.twidget.data

import com.tjg.twidget.core.UiMessages
import android.content.Context
import android.content.SharedPreferences
import com.tjg.twidget.schedule.ScheduleStore
import java.util.Locale
import org.json.JSONObject

/** Explicit account-key mappings: never replace arbitrary substrings or credentials. */
internal object AccountHandleMigration {
    const val REDIRECT_PREFIX = "account_redirect_"
    const val JOURNAL = "account_migration_pending"
    private var recovering = false
    private var persistenceFailed = false
    private fun durable(success: Boolean) {
        if (!success) { persistenceFailed = true; error(UiMessages.message(UiMessages.Key.ERROR_ACCOUNT_SAVE)) }
    }

    fun assertDestinationEmpty(context: Context, handle: String) {
        val next = handle.lowercase(Locale.US)
        val main = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        require(main.all.keys.none { mainKey(it, next, next) != null } && stores.none { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.keys.any { companionKey(name, it, next, next) != null }
        } && listOf("", ".pending").none { java.io.File(context.filesDir, "top_followers/$next.jsonl$it").exists() }) {
            UiMessages.message(UiMessages.Key.ERROR_ACCOUNT_EXISTS)
        }
    }

    fun begin(context: Context, old: String, next: String, values: Map<String, Any?>, removed: Set<String>) {
        val encoded = JSONObject()
        values.forEach { (key, value) -> encoded.put(key, JSONObject().put("type", when(value) {
            is Long -> "long"; is Int -> "int"; is Boolean -> "boolean"; is Float -> "float"; else -> "string"
        }).put("value", value)) }
        val journal = JSONObject().put("old", old).put("next", next).put("values", encoded)
            .put("removed", org.json.JSONArray(removed.toList())).put("published", false)
        durable(context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit().putString(JOURNAL, journal.toString()).commit())
        recover(context)
    }

    // A test can throw at these durable boundaries to model process death without killing the phone.
    internal var checkpoint: ((String) -> Unit)? = null

    fun recover(context: Context) {
        check(!persistenceFailed) { UiMessages.message(UiMessages.Key.ERROR_ACCOUNT_SAVE) }
        if (recovering) return
        val main = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val raw = main.getString(JOURNAL, null) ?: return
        recovering = true
        try {
            val journal = JSONObject(raw)
            val old = journal.getString("old")
            val next = journal.getString("next")
            if (!journal.getBoolean("published")) {
                copyCompanionData(context, old, next)
                checkpoint?.invoke("companions")
                val edit = main.edit()
                val removed = journal.getJSONArray("removed")
                for (i in 0 until removed.length()) edit.remove(removed.getString(i))
                val values = journal.getJSONObject("values")
                values.keys().forEach { key ->
                    val entry = values.getJSONObject(key)
                    val value: Any = when(entry.getString("type")) {
                        "long" -> entry.getLong("value"); "int" -> entry.getInt("value")
                        "boolean" -> entry.getBoolean("value"); "float" -> entry.getDouble("value").toFloat()
                        else -> entry.getString("value")
                    }
                    put(edit, key, value)
                }
                journal.put("published", true)
                durable(edit.putString(JOURNAL, journal.toString()).commit())
                checkpoint?.invoke("published")
            }
            removeSourceData(context, old)
            checkpoint?.invoke("cleanup")
            durable(main.edit().remove(JOURNAL).commit())
        } finally { recovering = false }
    }

    fun mainKey(key: String, oldHandle: String, newHandle: String): String? {
        val old = oldHandle.lowercase(Locale.US)
        val next = newHandle.lowercase(Locale.US)
        val exactPrefixes = listOf("profile_", "history_", "milestone_target_", "milestone_label_", "milestone_show_percent_")
        exactPrefixes.forEach { prefix -> if (key == prefix + old) return prefix + next }
        listOf("followers", "following", "posts", "likes").forEach { metric ->
            if (key == "chart_range_${old}_$metric") return "chart_range_${next}_$metric"
        }
        if (key.equals("today_open_$oldHandle", true)) return "today_open_$newHandle"
        return null
    }

    private val direct = listOf("twidget_imported_analytics", "twidget_interactions", "twidget_top_followers", "twidget_briefs")
    private val goalSuffixes = listOf("goals", "configured", "metric", "target", "auto", "auto_all")
    private val streakSuffixes = listOf("version", "latest_original_post", "window_start", "checked_at", "complete")
    private val stores = direct + listOf("twidget_account_goals", "twidget_daily_streak", "twidget_bangers", "twidget_analytics")

    fun companionKey(store: String, key: String, old: String, next: String): String? = when {
        store in direct && key == old -> next
        store == "twidget_account_goals" && goalSuffixes.any { key == "${old}_$it" } -> next + key.removePrefix(old)
        store == "twidget_daily_streak" && (key == old || streakSuffixes.any { key == "${old}_$it" }) -> next + key.removePrefix(old)
        store in listOf("twidget_bangers", "twidget_analytics") && key.endsWith(":$old") -> key.removeSuffix(old) + next
        else -> null
    }

    fun copyCompanionData(context: Context, oldHandle: String, newHandle: String) {
        com.tjg.twidget.brief.BriefPublishedReviewStore.copyAccount(context, oldHandle, newHandle)
        val old = oldHandle.lowercase(Locale.US)
        val next = newHandle.lowercase(Locale.US)
        stores.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            prefs.all.forEach { (key, value) ->
                val destination = companionKey(name, key, old, next)
                if (destination != null) {
                    // Only rewrite the cached profile owner, leaving post authors and links intact.
                    val updated = if (value is String && name in listOf("twidget_briefs", "twidget_analytics") &&
                        !key.startsWith("attempt:")) {
                        runCatching {
                            JSONObject(value).apply {
                                if (name == "twidget_briefs") put("username", newHandle) else put("userName", newHandle)
                            }.toString()
                        }.getOrDefault(value) // Preserve malformed optional caches; readers already ignore them.
                    } else value
                    put(edit, destination, updated)
                }
            }
            // Sources intentionally survive until account selection commits, making retries safe.
            durable(edit.commit())
        }
        listOf("", ".pending").forEach { suffix ->
            val source = java.io.File(context.filesDir, "top_followers/${old}.jsonl$suffix")
            if (source.exists()) {
                val target = java.io.File(context.filesDir, "top_followers/${next}.jsonl$suffix")
                target.parentFile?.mkdirs()
                java.io.FileOutputStream(target).use { output -> source.inputStream().use { it.copyTo(output) }; output.fd.sync() }
            }
        }
        ScheduleStore(context).transaction { posts ->
            posts.replaceAll { post ->
                if (post.accountUsername.trim().trimStart('@').equals(oldHandle, true)) {
                    post.copy(
                        accountUsername = newHandle,
                        accountId = if (post.accountId?.trim()?.trimStart('@').equals(oldHandle, true)) newHandle else post.accountId,
                    )
                } else post
            }
        }
    }

    fun removeSourceData(context: Context, oldHandle: String) {
        com.tjg.twidget.brief.BriefPublishedReviewStore.removeAccount(context, oldHandle)
        val old = oldHandle.lowercase(Locale.US)
        stores.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            prefs.all.keys.filter { key -> companionKey(name, key, old, old) != null }
                .forEach(edit::remove)
            durable(edit.commit())
        }
        listOf("", ".pending").forEach { suffix ->
            val source = java.io.File(context.filesDir, "top_followers/${old}.jsonl$suffix")
            check(!source.exists() || source.delete()) { "Unable to clean up the old follower archive" }
        }
    }

    fun put(edit: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> edit.putString(key, value)
            is Long -> edit.putLong(key, value)
            is Int -> edit.putInt(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Float -> edit.putFloat(key, value)
            else -> error("Unsupported account preference: $key")
        }
    }
}
