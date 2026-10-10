package com.tjg.twidget.followers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class TopFollower(
    val id: String,
    val username: String,
    val name: String,
    val followers: Long,
    val verified: Boolean,
    val avatarUrl: String,
    val scanIndex: Int = 0,
    val mutual: Boolean? = null,
)

data class TopFollowersState(
    val top: List<TopFollower> = emptyList(),
    val cursor: String = "",
    val pages: Int = 0,
    val scanned: Int = 0,
    val scanning: Boolean = false,
    val complete: Boolean = false,
    val error: String = "",
    val startedAt: Long = 0L,
    val lastStartedDay: String = "",
    val completedAt: Long = 0L,
    val activeRunId: String = "",
)

object TopFollowersStore {
    private const val PREFS = "twidget_top_followers"

    fun read(context: Context, username: String): TopFollowersState {
        val raw = prefs(context).getString(key(username), null) ?: return TopFollowersState()
        return runCatching {
            val root = JSONObject(raw)
            val users = root.optJSONArray("top") ?: JSONArray()
            TopFollowersState(
                top = buildList {
                    for (index in 0 until users.length()) {
                        val user = users.getJSONObject(index)
                        add(TopFollower(
                            id = user.optString("id"),
                            username = user.optString("username"),
                            name = user.optString("name"),
                            followers = user.optLong("followers"),
                            verified = user.optBoolean("verified"),
                            avatarUrl = user.optString("avatar"),
                            scanIndex = user.optInt("scanIndex"),
                            mutual = user.optNullableBoolean("mutual"),
                        ))
                    }
                },
                cursor = root.optString("cursor"),
                pages = root.optInt("pages"),
                scanned = root.optInt("scanned"),
                scanning = root.optBoolean("scanning"),
                complete = root.optBoolean("complete"),
                error = root.optString("error"),
                startedAt = root.optLong("startedAt"),
                lastStartedDay = root.optString("lastStartedDay"),
                completedAt = root.optLong("completedAt"),
                activeRunId = root.optString("activeRunId"),
            )
        }.getOrDefault(TopFollowersState())
    }

    fun write(context: Context, username: String, state: TopFollowersState) {
        val top = JSONArray()
        state.top.forEach { user ->
            top.put(JSONObject().apply {
                put("id", user.id)
                put("username", user.username)
                put("name", user.name)
                put("followers", user.followers)
                put("verified", user.verified)
                put("avatar", user.avatarUrl)
                if (user.scanIndex > 0) put("scanIndex", user.scanIndex)
                user.mutual?.let { put("mutual", it) }
            })
        }
        val root = JSONObject().apply {
            put("top", top)
            put("cursor", state.cursor)
            put("pages", state.pages)
            put("scanned", state.scanned)
            put("scanning", state.scanning)
            put("complete", state.complete)
            put("error", state.error)
            put("startedAt", state.startedAt)
            put("lastStartedDay", state.lastStartedDay)
            put("completedAt", state.completedAt)
            put("activeRunId", state.activeRunId)
        }
        prefs(context).edit().putString(key(username), root.toString()).apply()
    }

    @Synchronized
    fun clear(context: Context, username: String) {
        prefs(context).edit().remove(key(username)).apply()
        TopFollowersArchiveStore.clear(context, username)
        androidx.work.WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("twidget-top-followers-bridge-${key(username)}")
    }

    @Synchronized
    fun stopScan(context: Context, username: String): TopFollowersState {
        val stopped = read(context, username).copy(scanning = false, error = "", activeRunId = "")
        write(context, username, stopped)
        return stopped
    }

    /** Preserve completed rankings, but discard state belonging to removed on-device scans. */
    fun clearLocalScanState(context: Context) {
        prefs(context).all.keys.forEach { username ->
            val previous = read(context, username)
            if (previous.scanning || previous.activeRunId.isNotBlank() || previous.cursor.isNotBlank()) {
                write(context, username, previous.copy(scanning = false, activeRunId = "", cursor = "", error = ""))
            }
        }
    }

    private fun key(username: String) = username.trim().trimStart('@').lowercase(Locale.US)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

internal fun rankedTopFollowers(users: List<TopFollower>, limit: Int = 5): List<TopFollower> =
    users
        .distinctBy { it.id.ifBlank { it.username.lowercase(Locale.US) } }
        .sortedByDescending { it.followers }
        .take(limit)
