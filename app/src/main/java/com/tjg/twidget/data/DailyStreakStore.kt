package com.tjg.twidget.data

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.json.JSONArray

data class StreakSnapshot(
    val streak: Int,
    val activeToday: Boolean,
    val lastActiveDay: String?,
    val lastOriginalPostAt: Long = 0L,
    val activityWindowStartAt: Long = 0L,
    val activityCheckedAt: Long = 0L,
    val activityComplete: Boolean = false,
)

object DailyStreakStore {
    private const val PREFS = "twidget_daily_streak"
    private const val DATA_VERSION = 2

    fun mergeOriginalActivity(
        context: Context,
        username: String,
        timestamps: Set<Long>,
        windowStartAt: Long,
        checkedAt: Long,
        complete: Boolean,
    ): Set<String> {
        val accountKey = key(username)
        val preferences = prefs(context)
        val migrated = preferences.getInt(versionKey(accountKey), 0) == DATA_VERSION
        val existing = if (migrated) activeDays(context, username) else emptySet()
        val detected = timestamps.filter { it > 0L }.mapTo(mutableSetOf()) { localDayKey(it) }
        val merged = existing + detected
        val latestDetected = timestamps.maxOrNull() ?: 0L
        val previousLatest = if (migrated) preferences.getLong(latestPostKey(accountKey), 0L) else 0L
        preferences.edit()
            .putString(accountKey, JSONArray(merged.sorted()).toString())
            .putInt(versionKey(accountKey), DATA_VERSION)
            .putLong(latestPostKey(accountKey), maxOf(previousLatest, latestDetected))
            .putLong(windowStartKey(accountKey), windowStartAt)
            .putLong(checkedAtKey(accountKey), checkedAt)
            .putBoolean(completeKey(accountKey), complete)
            .apply()
        return merged
    }

    fun snapshot(context: Context, username: String): StreakSnapshot {
        val accountKey = key(username)
        val preferences = prefs(context)
        val currentVersion = preferences.getInt(versionKey(accountKey), 0)
        // Version 1 mixed replies with original posts and cannot be repaired
        // from the stored day alone. Hide it until the next activity refresh
        // rebuilds recent days using the original-tweet-only policy.
        val days = if (currentVersion == DATA_VERSION) activeDays(context, username) else emptySet()
        val today = LocalDate.now(ZoneId.systemDefault())
        val todayKey = today.toString()
        val activeToday = todayKey in days
        val streak = computeStreak(days, today)
        val lastActiveDay = days.maxOrNull()
        return StreakSnapshot(
            streak = streak,
            activeToday = activeToday,
            lastActiveDay = lastActiveDay,
            lastOriginalPostAt = preferences.getLong(latestPostKey(accountKey), 0L),
            activityWindowStartAt = preferences.getLong(windowStartKey(accountKey), 0L),
            activityCheckedAt = preferences.getLong(checkedAtKey(accountKey), 0L),
            activityComplete = currentVersion == DATA_VERSION &&
                preferences.getBoolean(completeKey(accountKey), false),
        )
    }

    fun activeDays(context: Context, username: String): Set<String> {
        val raw = prefs(context).getString(key(username), null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet {
                for (index in 0 until array.length()) {
                    val day = array.optString(index)
                    if (day.isNotBlank()) add(day)
                }
            }
        }.getOrDefault(emptySet())
    }

    internal fun computeStreak(activeDays: Set<String>, today: LocalDate): Int {
        var cursor = if (activeDays.contains(today.toString())) {
            today
        } else {
            today.minusDays(1)
        }
        if (!activeDays.contains(cursor.toString())) return 0
        var streak = 0
        while (activeDays.contains(cursor.toString())) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    internal fun localDayKey(timestamp: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDate().toString()

    private fun key(username: String) =
        username.trim().trimStart('@').lowercase(Locale.US)

    private fun versionKey(accountKey: String) = "${accountKey}_version"
    private fun latestPostKey(accountKey: String) = "${accountKey}_latest_original_post"
    private fun windowStartKey(accountKey: String) = "${accountKey}_window_start"
    private fun checkedAtKey(accountKey: String) = "${accountKey}_checked_at"
    private fun completeKey(accountKey: String) = "${accountKey}_complete"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
