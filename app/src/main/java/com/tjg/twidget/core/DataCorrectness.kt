package com.tjg.twidget.core

import com.tjg.twidget.data.HistorySample
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Pure provider and data rules kept separate from Android/network code. */
internal object ProviderFallback {
    fun <T> directThenOptionalFallback(
        direct: () -> T,
        fallback: (() -> T)?,
    ): T {
        val directError = try {
            return direct()
        } catch (error: Exception) {
            error
        }
        if (fallback == null) throw directError
        return try {
            fallback()
        } catch (_: Exception) {
            // The direct provider is the selected source, so preserve its
            // actionable error when an optional fallback also fails.
            throw directError
        }
    }
}

internal data class FxStatusCandidate(
    val type: String,
    val authorUsername: String,
    val url: String,
    val timestamp: Long,
    val id: String,
    val conversationId: String,
    val isRepost: Boolean,
    val isReply: Boolean,
)

internal object FxPostPolicy {
    fun isOwnOriginalInWindow(
        status: FxStatusCandidate,
        requestedUsername: String,
        windowStart: Long,
        now: Long,
    ): Boolean {
        val username = requestedUsername.trim().trimStart('@').lowercase(Locale.US)
        if (status.type != "status") return false
        if (status.authorUsername.lowercase(Locale.US) != username) return false
        if (status.isRepost || status.isReply) return false
        if (!status.url.lowercase(Locale.US).contains("/$username/status/")) return false
        if (status.timestamp !in windowStart..(now + 5 * 60 * 1000L)) return false
        return status.conversationId.isBlank() || status.id.isBlank() ||
            status.conversationId == status.id
    }
}

internal object AnalyticsPaging {
    fun reachedWindowBoundary(pageTimestamps: List<Long>, windowStart: Long): Boolean {
        if (pageTimestamps.isEmpty()) return true
        val lastDatedTimestamp = pageTimestamps.asReversed().firstOrNull { it > 0L }
        return lastDatedTimestamp != null && lastDatedTimestamp <= windowStart
    }
}

internal data class KnownLong(val value: Long, val known: Boolean)

internal object MetricProvenance {
    fun preferLiveThenPrevious(
        liveValue: Long?,
        liveKnown: Boolean,
        previousValue: Long,
        previousKnown: Boolean,
    ): KnownLong = when {
        liveValue != null && liveKnown -> KnownLong(liveValue, true)
        previousKnown -> KnownLong(previousValue, true)
        else -> KnownLong(0L, false)
    }
}

internal object HistoryDates {
    private val monthNames = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /**
     * Converts a server UTC timestamp/day label into midnight in the device
     * timezone without allowing a UTC offset to move the sample to another day.
     */
    fun localDayFromServer(
        timestamp: Long,
        dayLabel: String,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timestamp
        }
        val match = Regex("^([A-Z][a-z]{2})\\s+(\\d{1,2})$").matchEntire(dayLabel.trim())
        val month = match?.groupValues?.get(1)?.let(monthNames::indexOf)?.takeIf { it >= 0 }
            ?: utc.get(Calendar.MONTH)
        val day = match?.groupValues?.get(2)?.toIntOrNull() ?: utc.get(Calendar.DAY_OF_MONTH)
        return (utc.get(Calendar.YEAR) - 1..utc.get(Calendar.YEAR) + 1)
            .mapNotNull { year ->
                Calendar.getInstance(timeZone).apply {
                    clear()
                    isLenient = false
                    set(year, month, day, 0, 0, 0)
                }.let { calendar -> runCatching { calendar.timeInMillis }.getOrNull() }
            }
            .minByOrNull { abs(it - timestamp) }
            ?: timestamp
    }
}

internal object HistoryMigrationPolicy {
    fun matchesLegacySeededRamp(
        samples: List<HistorySample>,
        expectedGains: List<Long>,
        startOfDay: (Long) -> Long,
    ): Boolean {
        if (samples.size < expectedGains.size + 1) return false
        val seeded = samples.take(expectedGains.size + 1)
        val consecutive = seeded.zipWithNext().all { (previous, current) ->
            startOfDay(current.timestamp) - startOfDay(previous.timestamp) == 24 * 60 * 60 * 1000L
        }
        if (!consecutive) return false
        return seeded.zipWithNext().map { (previous, current) ->
            current.followers - previous.followers
        } == expectedGains
    }
}
