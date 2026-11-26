package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAgeComparison
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import java.time.Instant
import java.time.ZoneId

internal enum class BriefPostingWindow(val labelRes: Int, val startHour: Int, val endHour: Int) {
    MORNING(R.string.brief_window_morning, 5, 12),
    AFTERNOON(R.string.brief_window_afternoon, 12, 17),
    EVENING(R.string.brief_window_evening, 17, 23),
    LATE_NIGHT(R.string.brief_window_late_night, 23, 5);

    fun contains(hour: Int) = if (startHour < endHour) hour in startHour until endHour
        else hour >= startHour || hour < endHour

    companion object {
        fun at(hour: Int) = entries.first { it.contains(hour) }
    }
}

internal enum class BriefPostingPhase { UPCOMING, NOW, NEXT_DAY }
internal data class BriefPostingSignal(val window: BriefPostingWindow, val sampleSize: Int, val ratio: Double)

/** Observational advice only. Unknown views and mismatched observation ages cannot win a window. */
internal object BriefPostingTiming {
    private const val DAY = 86_400_000L

    fun signal(analytics: PostAnalytics?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): BriefPostingSignal? {
        analytics ?: return null
        if (analytics.cachedAt !in (now - 7 * DAY)..now) return null
        val posts = analytics.recentPosts.distinctBy(PostSummary::url).filter {
            PostAgeComparison.valid(it) && it.viewsKnown && !it.isReply &&
                it.authorUserName.equals(analytics.userName, true) && it.timestamp in (now - 90 * DAY)..now &&
                (PostAgeComparison.age(it, analytics) ?: 0) >= DAY
        }
        val comparable = PostAgeComparison.cohort(posts, analytics)
        val groups = comparable.groupBy { BriefPostingWindow.at(Instant.ofEpochMilli(it.timestamp).atZone(zone).hour) }
            .filterValues { it.size >= 3 }
        if (groups.size < 2) return null
        val medians = groups.mapValues { (_, posts) ->
            val sorted = posts.map { it.views.toDouble() }.sorted()
            val mid = sorted.size / 2
            if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1] / 2 + sorted[mid] / 2
        }
        val winner = medians.maxByOrNull { it.value } ?: return null
        // Beat every other eligible window, rather than an average hiding a close second.
        val runnerUp = medians.filterKeys { it != winner.key }.values.maxOrNull() ?: return null
        if (runnerUp <= 0 || winner.value / runnerUp < 1.35) return null
        return BriefPostingSignal(winner.key, groups.values.sumOf { it.size }, winner.value / runnerUp)
    }

    fun phase(window: BriefPostingWindow, hour: Int): BriefPostingPhase = when {
        window.contains(hour) -> BriefPostingPhase.NOW
        hour < window.startHour -> BriefPostingPhase.UPCOMING
        else -> BriefPostingPhase.NEXT_DAY
    }

    fun card(signal: BriefPostingSignal, strings: BriefStrings, hour: Int): BriefCard {
        val phase = phase(signal.window, hour)
        val label = strings.text(signal.window.labelRes)
        return BriefCard(
            id = "posting-window-${signal.window.name.lowercase()}", type = BriefCardType.POSTING_GUIDE,
            title = strings.text(when (phase) {
                BriefPostingPhase.UPCOMING -> R.string.brief_timing_upcoming
                BriefPostingPhase.NOW -> R.string.brief_timing_now
                BriefPostingPhase.NEXT_DAY -> R.string.brief_timing_next
            }, label),
            body = strings.text(R.string.brief_timing_evidence, label), score = 86,
            rankSignals = BriefRankSignals(contextRelevance = .82, timeRelevance = if (phase == BriefPostingPhase.NOW) .95 else .7),
        )
    }
}
