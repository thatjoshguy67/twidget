package com.tjg.twidget.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Scan differences cover an interval, never an invented day of unfollows. */
data class FollowerLossObservation(val from: Long, val to: Long, val lost: Long)

object FollowerLossStore {
    private const val PREFS = "twidget_follower_losses"

    internal fun decode(body: String, userId: String): List<FollowerLossObservation>? {
        val root = JSONObject(body)
        if (!userId.matches(Regex("[0-9]+")) || root.optString("userId") != userId) return null
        val array = root.optJSONArray("followerChanges") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            if (row.optString("userId") != userId || row.optString("source") != "complete_follower_scans" ||
                row.optString("semantics") != "observed_membership_changes_not_daily_unfollow_events") return@mapNotNull null
            val from = row.optLong("from", -1)
            val to = row.optLong("to", -1)
            val lost = row.optLong("lostFollowers", -1)
            if (from <= 0 || to <= from || lost < 0) null else FollowerLossObservation(from, to, lost)
        }.sortedBy { it.to }.takeLast(366)
    }

    internal fun save(context: Context, userId: String, observations: List<FollowerLossObservation>) {
        val array = JSONArray().apply { observations.forEach { row ->
            put(JSONObject().put("from", row.from).put("to", row.to).put("lost", row.lost))
        } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(userId, array.toString()).apply()
    }

    fun latest(context: Context, userId: String, now: Long = System.currentTimeMillis()): FollowerLossObservation? {
        if (userId.isBlank()) return null
        return runCatching {
            val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(userId, "[]"))
            (0 until array.length()).map { array.getJSONObject(it) }
                .map { FollowerLossObservation(it.getLong("from"), it.getLong("to"), it.getLong("lost")) }
                .filter { it.to <= now && it.to >= now - 7 * 86_400_000L && it.from > 0 && it.to > it.from }
                .maxByOrNull { it.to }
        }.getOrNull()
    }
}
