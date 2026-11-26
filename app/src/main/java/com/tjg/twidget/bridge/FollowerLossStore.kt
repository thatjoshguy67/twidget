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

    fun all(context: Context, userId: String): List<FollowerLossObservation> {
        if (userId.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(userId, "[]"))
            (0 until array.length()).map { array.getJSONObject(it) }
                .map { FollowerLossObservation(it.getLong("from"), it.getLong("to"), it.getLong("lost")) }
        }.getOrDefault(emptyList())
    }

    fun latest(context: Context, userId: String, now: Long = System.currentTimeMillis()): FollowerLossObservation? =
        all(context, userId).filter { it.to <= now && it.to >= now - 7 * 86_400_000L && it.from > 0 && it.to > it.from }.maxByOrNull { it.to }

    /** Only short, consecutive-day scan intervals belong in daily chart slots. */
    internal fun dailyObservations(rows: List<FollowerLossObservation>, zone: java.time.ZoneId, now: Long): Map<java.time.LocalDate, FollowerLossObservation> =
        rows.filter { row ->
            val from = java.time.Instant.ofEpochMilli(row.from).atZone(zone).toLocalDate()
            val to = java.time.Instant.ofEpochMilli(row.to).atZone(zone).toLocalDate()
            row.lost >= 0 && row.from > 0 && row.to > row.from && row.to <= now && row.to - row.from <= 36 * 3_600_000L && to == from.plusDays(1)
        }.groupBy { java.time.Instant.ofEpochMilli(it.to).atZone(zone).toLocalDate() }
            .mapValues { (_, rows) -> rows.maxBy { it.to } }
}
