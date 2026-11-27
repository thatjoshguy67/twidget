package com.tjg.twidget.main

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

enum class CompareEmphasis { BOTH, LEFT, RIGHT, NEITHER }
enum class DeltaTone { POSITIVE, NEGATIVE, NEUTRAL }
internal data class CompareHistoryPoint(val timestamp: Long, val followers: Long)
internal data class ComparePostCounts(val likes: Long?, val reposts: Long?, val replies: Long?, val quotes: Long?, val views: Long?)
internal data class ComparePostTotals(val likes: Long?, val reposts: Long?, val views: Long?, val engagementRate: Double?)
internal object CompareLogic {
    fun emphasis(left: Double?, right: Double?): CompareEmphasis = when {
        left == null && right == null -> CompareEmphasis.NEITHER
        left == null -> CompareEmphasis.RIGHT
        right == null -> CompareEmphasis.LEFT
        left > right -> CompareEmphasis.LEFT
        right > left -> CompareEmphasis.RIGHT
        else -> CompareEmphasis.BOTH
    }
    fun totals(posts: List<ComparePostCounts>): ComparePostTotals {
        fun total(select: (ComparePostCounts) -> Long?): Long? {
            if (posts.isEmpty()) return null
            val values = posts.map(select)
            return if (values.any { it == null || it < 0 }) null else values.filterNotNull().sum()
        }
        val likes = total { it.likes }
        val reposts = total { it.reposts }
        val replies = total { it.replies }
        val quotes = total { it.quotes }
        val views = total { it.views }
        val rate = if (likes != null && reposts != null && replies != null && quotes != null && views != null && views > 0)
            (likes.toDouble() + reposts + replies + quotes) / views else null
        return ComparePostTotals(likes, reposts, views, rate)
    }
    fun weeklyDelta(current: Long?, history: List<CompareHistoryPoint>, now: Long): Long? {
        if (current == null) return null
        return com.tjg.twidget.data.FollowerMetrics.weeklyDelta(
            com.tjg.twidget.data.ProfileStats("", "", current, 0, 0, 0, syncedAt = now),
            history.map { com.tjg.twidget.data.HistorySample("", it.followers, 0, 0, 0, it.timestamp) },
        )
    }
    fun tone(delta: Long): DeltaTone = when { delta > 0 -> DeltaTone.POSITIVE; delta < 0 -> DeltaTone.NEGATIVE; else -> DeltaTone.NEUTRAL }
    fun signed(delta: Long, locale: Locale): String = (if (delta > 0) "+" else "") + NumberFormat.getIntegerInstance(locale).format(delta)
    fun compact(value: Long, locale: Locale): String {
        val absolute = abs(value.toDouble())
        val (divisor, suffix) = when {
            absolute >= 999_950_000_000 -> 1_000_000_000_000.0 to "T"
            absolute >= 999_950_000 -> 1_000_000_000.0 to "B"
            absolute >= 999_950 -> 1_000_000.0 to "M"
            absolute >= 1_000 -> 1_000.0 to "K"
            else -> return NumberFormat.getIntegerInstance(locale).format(value)
        }
        return NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 0 }.format(value / divisor) + suffix
    }
}
