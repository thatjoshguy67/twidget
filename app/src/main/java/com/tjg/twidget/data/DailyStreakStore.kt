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
)

object DailyStreakStore {
    private const val PREFS = "twidget_daily_streak"

    fun mergeDays(context: Context, username: String, days: Set<String>): Set<String> {
        if (days.isEmpty()) return activeDays(context, username)
        val merged = activeDays(context, username) + days
        prefs(context).edit()
            .putString(key(username), JSONArray(merged.sorted()).toString())
            .apply()
        return merged
    }

    fun snapshot(context: Context, username: String): StreakSnapshot {
        val days = activeDays(context, username)
        val today = LocalDate.now(ZoneId.systemDefault())
        val todayKey = today.toString()
        val activeToday = todayKey in days
        val streak = computeStreak(days, today)
        val lastActiveDay = days.maxOrNull()
        return StreakSnapshot(streak = streak, activeToday = activeToday, lastActiveDay = lastActiveDay)
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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
