package com.tjg.twidget.data

import org.json.JSONObject

/** Shared correlation evidence, never proof that an individual follower was a bot. */
data class FollowerContext(val assessedAt: Long, val expiresAt: Long,
    val eventId: String = "", val startAt: Long = 0, val endAt: Long = 0) {
    fun overlapsDecline(stats: ProfileStats, history: List<HistorySample>, now: Long = System.currentTimeMillis()): Boolean {
        if (eventId.isBlank() || assessedAt <= 0 || assessedAt > now + 300_000 || now > expiresAt || expiresAt - assessedAt > 6 * 3_600_000 ||
            endAt <= startAt || stats.syncedAt !in 1..now || now - stats.syncedAt > 2 * 86_400_000) return false
        val points = (history.filter { !it.estimated && it.followersKnown && it.followers >= 0 } +
            if (stats.followersKnown) listOf(HistorySample("", stats.followersCount, 0, 0, 0, stats.syncedAt)) else emptyList())
            .filter { it.timestamp in 1..stats.syncedAt }.sortedBy { it.timestamp }
        return points.zipWithNext().any { (a, b) -> b.timestamp > a.timestamp && b.timestamp - a.timestamp <= 2 * 86_400_000 &&
            a.timestamp < endAt && b.timestamp >= startAt && b.followers < a.followers }
    }
    fun toJson() = JSONObject().put("schemaVersion", 1).put("assessedAt", assessedAt).put("expiresAt", expiresAt)
        .put("status", if (eventId.isBlank()) "no_event" else "detected")
        .put("event", if (eventId.isBlank()) JSONObject.NULL else JSONObject().put("id", eventId)
            .put("type", "widespread_follower_decline").put("startAt", startAt).put("endAt", endAt))
    companion object {
        fun parse(root: JSONObject?): FollowerContext? {
            root ?: return null
            if (root.optInt("schemaVersion") != 1) return null
            val event = root.optJSONObject("event")?.takeIf { root.optString("status") == "detected" && it.optString("type") == "widespread_follower_decline" }
            return FollowerContext(root.optLong("assessedAt"), root.optLong("expiresAt"), event?.optString("id").orEmpty(),
                event?.optLong("startAt") ?: 0, event?.optLong("endAt") ?: 0)
        }
    }
}
