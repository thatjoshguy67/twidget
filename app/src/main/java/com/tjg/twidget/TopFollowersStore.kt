package com.tjg.twidget

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
)

data class TopFollowersState(
    val top: List<TopFollower> = emptyList(),
    val cursor: String = "",
    val pages: Int = 0,
    val scanned: Int = 0,
    val scanning: Boolean = false,
    val complete: Boolean = false,
    val error: String = "",
    val completedAt: Long = 0L,
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
                        ))
                    }
                },
                cursor = root.optString("cursor"),
                pages = root.optInt("pages"),
                scanned = root.optInt("scanned"),
                scanning = root.optBoolean("scanning"),
                complete = root.optBoolean("complete"),
                error = root.optString("error"),
                completedAt = root.optLong("completedAt"),
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
            put("completedAt", state.completedAt)
        }
        prefs(context).edit().putString(key(username), root.toString()).apply()
    }

    fun reset(context: Context, username: String) = write(
        context,
        username,
        TopFollowersState(scanning = true),
    )

    private fun key(username: String) = username.trim().trimStart('@').lowercase(Locale.US)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun rankedTopFollowers(users: List<TopFollower>, limit: Int = 5): List<TopFollower> =
    users
        .distinctBy { it.id.ifBlank { it.username.lowercase(Locale.US) } }
        .sortedByDescending { it.followers }
        .take(limit)
