package com.tjg.twidget.followers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
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
    val startedAt: Long = 0L,
    val lastStartedDay: String = "",
    val completedAt: Long = 0L,
    val activeRunId: String = "",
)

enum class TopFollowersScanStart {
    STARTED,
    ALREADY_SCANNED_TODAY,
    NO_API_KEY,
}

internal object TopFollowersScanPolicy {
    fun localDay(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString()

    fun canStart(lastStartedDay: String, timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): Boolean =
        lastStartedDay != localDay(timestamp, zoneId)

    fun canStart(
        lastStartedDay: String,
        previousScanComplete: Boolean,
        timestamp: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        dailyLimitEnabled: Boolean = true,
    ): Boolean = !dailyLimitEnabled || !previousScanComplete || canStart(lastStartedDay, timestamp, zoneId)
}

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
    fun tryStartScan(
        context: Context,
        username: String,
        runId: String,
        dailyLimitEnabled: Boolean = true,
        now: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TopFollowersScanStart {
        val previous = read(context, username)
        if (!TopFollowersScanPolicy.canStart(
                previous.lastStartedDay,
                previous.complete,
                now,
                zoneId,
                dailyLimitEnabled,
            )
        ) {
            return TopFollowersScanStart.ALREADY_SCANNED_TODAY
        }
        if (previous.lastStartedDay == TopFollowersScanPolicy.localDay(now, zoneId) && !previous.complete) {
            write(context, username, previous.copy(scanning = true, error = "", activeRunId = runId))
            return TopFollowersScanStart.STARTED
        }
        write(
            context,
            username,
            TopFollowersState(
                scanning = true,
                startedAt = now,
                lastStartedDay = TopFollowersScanPolicy.localDay(now, zoneId),
                activeRunId = runId,
            ),
        )
        return TopFollowersScanStart.STARTED
    }

    @Synchronized
    fun isRunCurrent(context: Context, username: String, runId: String): Boolean =
        runId.isNotBlank() && read(context, username).activeRunId == runId

    @Synchronized
    fun writeForRun(
        context: Context,
        username: String,
        runId: String,
        state: TopFollowersState,
        finished: Boolean = false,
    ): Boolean {
        if (!isRunCurrent(context, username, runId)) return false
        write(context, username, state.copy(activeRunId = if (finished) "" else runId))
        return true
    }

    @Synchronized
    fun stopScan(context: Context, username: String): TopFollowersState {
        val stopped = read(context, username).copy(scanning = false, error = "", activeRunId = "")
        write(context, username, stopped)
        return stopped
    }

    private fun key(username: String) = username.trim().trimStart('@').lowercase(Locale.US)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun rankedTopFollowers(users: List<TopFollower>, limit: Int = 5): List<TopFollower> =
    users
        .distinctBy { it.id.ifBlank { it.username.lowercase(Locale.US) } }
        .sortedByDescending { it.followers }
        .take(limit)
