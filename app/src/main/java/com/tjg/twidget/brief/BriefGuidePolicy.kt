package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAgeComparison
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Evidence thresholds for advice shown by the Brief. */
internal object BriefGuidePolicy {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MATCH_WINDOW_MS = 3 * DAY_MS
    private const val FOLLOW_UP_WINDOW_MS = 3 * DAY_MS

    fun postingCard(
        analytics: PostAnalytics?,
        strings: BriefStrings,
        now: Long = System.currentTimeMillis(),
    ): BriefCard? {
        analytics ?: return null
        val posts = analytics.recentPosts.distinctBy(PostSummary::url).filter {
            PostAgeComparison.valid(it) && it.authorUserName.equals(analytics.userName, ignoreCase = true) &&
                analytics.cachedAt <= now &&
                (PostAgeComparison.age(it, analytics) ?: 0L) >= DAY_MS
        }
        if (posts.size < 4) return null
        followUpCard(posts, analytics, now, strings)?.let { return it }
        // Group advice needs age overlap too: an older media cohort must not
        // win just because its counts have had longer to accumulate.
        val comparable = PostAgeComparison.cohort(posts, analytics)
        postingWindowCard(comparable, strings)?.let { return it }
        return formatExperimentCard(comparable, strings)
    }

    private fun followUpCard(
        posts: List<PostSummary>,
        analytics: PostAnalytics,
        now: Long,
        strings: BriefStrings,
    ): BriefCard? {
        val post = posts.asSequence()
            .filter { now - it.timestamp in DAY_MS..FOLLOW_UP_WINDOW_MS }
            .mapNotNull { post ->
                val baseline = PostAgeComparison.baseline(post, analytics) ?: return@mapNotNull null
                val performanceRatio = maxOf(
                    baseline.ratio(post, PostSummary::views) ?: 0.0,
                    baseline.ratio(post, PostSummary::engagements) ?: 0.0,
                )
                (post to performanceRatio).takeIf { performanceRatio >= 1.6 }
            }
            .maxByOrNull { (_, ratio) -> ratio }?.first ?: return null
        return BriefCard(
            id = "posting-follow-up-${post.timestamp}",
            type = BriefCardType.POSTING_GUIDE,
            title = strings.text(R.string.brief_guide_follow_up_title),
            body = strings.text(R.string.brief_guide_follow_up_body),
            score = 93,
            action = BriefCardAction.NONE,
            rankSignals = BriefRankSignals(
                contextRelevance = 0.95,
                timeRelevance = 0.4,
                occurredAt = post.timestamp,
                freshForMillis = FOLLOW_UP_WINDOW_MS,
                validUntil = post.timestamp + FOLLOW_UP_WINDOW_MS,
            ),
        )
    }

    private enum class PostingWindow(val id: String, val labelRes: Int) {
        MORNING("morning", R.string.brief_window_morning),
        AFTERNOON("afternoon", R.string.brief_window_afternoon),
        EVENING("evening", R.string.brief_window_evening),
        LATE_NIGHT("late-night", R.string.brief_window_late_night),
    }

    private fun postingWindowCard(posts: List<PostSummary>, strings: BriefStrings): BriefCard? {
        val groups = posts.groupBy { post ->
            val hour = Instant.ofEpochMilli(post.timestamp).atZone(ZoneId.systemDefault()).hour
            when (hour) {
                in 5..11 -> PostingWindow.MORNING
                in 12..16 -> PostingWindow.AFTERNOON
                in 17..22 -> PostingWindow.EVENING
                else -> PostingWindow.LATE_NIGHT
            }
        }.filterValues { it.size >= 2 }
        if (groups.size < 2) return null
        val medians = groups.mapValues { (_, values) -> medianPerformance(values) }
        val winner = medians.maxByOrNull(Map.Entry<PostingWindow, Double>::value) ?: return null
        val others = medians.filterKeys { it != winner.key }.values.average()
        if (others <= 0.0 || winner.value / others < 1.35) return null
        val label = strings.text(winner.key.labelRes)
        return BriefCard(
            id = "posting-window-${winner.key.id}",
            type = BriefCardType.POSTING_GUIDE,
            title = strings.text(R.string.brief_guide_window_title, label),
            body = strings.text(R.string.brief_guide_window_body, label),
            score = 86,
            action = BriefCardAction.NONE,
            rankSignals = BriefRankSignals(contextRelevance = 0.82, timeRelevance = 0.7),
        )
    }

    private fun formatExperimentCard(posts: List<PostSummary>, strings: BriefStrings): BriefCard? {
        val withMedia = posts.filter { it.media.isNotEmpty() }
        val textOnly = posts.filter { it.media.isEmpty() }
        if (withMedia.size < 2 || textOnly.size < 2) return null
        val mediaMedian = medianPerformance(withMedia)
        val textMedian = medianPerformance(textOnly)
        if (mediaMedian <= 0.0 || textMedian <= 0.0) return null
        val mediaWins = mediaMedian > textMedian
        val ratio = if (mediaWins) mediaMedian / textMedian else textMedian / mediaMedian
        if (ratio < 1.5) return null
        return BriefCard(
            id = "posting-format-${if (mediaWins) "media" else "text"}",
            type = BriefCardType.POSTING_GUIDE,
            title = strings.text(
                if (mediaWins) R.string.brief_guide_format_media_title else R.string.brief_guide_format_text_title,
            ),
            body = strings.text(
                if (mediaWins) R.string.brief_guide_format_media_body else R.string.brief_guide_format_text_body,
            ),
            score = 84,
            action = BriefCardAction.NONE,
            rankSignals = BriefRankSignals(contextRelevance = 0.78, timeRelevance = 0.65),
        )
    }

    private fun medianPerformance(posts: List<PostSummary>): Double {
        val values = posts.map { it.views.toDouble() }.sorted()
        val middle = values.size / 2
        return if (values.size % 2 == 1) values[middle]
        else values[middle - 1] / 2.0 + values[middle] / 2.0
    }

    private fun timestampsMatch(left: Long, right: Long): Boolean =
        left > 0L && right > 0L && kotlin.math.abs(left - right) <= MATCH_WINDOW_MS

    private fun textMatches(left: String, right: String): Boolean {
        val a = normalizeText(left)
        val b = normalizeText(right)
        if (a.length < 20 || b.length < 20) return false
        return a == b || a.startsWith(b) || b.startsWith(a)
    }

    private fun normalizeText(value: String): String = value
        .lowercase(Locale.US)
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
