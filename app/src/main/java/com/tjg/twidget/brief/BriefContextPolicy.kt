package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAgeComparison
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Ordinary-day facts from existing snapshots; no extrapolation or inferred causes. */
internal object BriefContextPolicy {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A partial history must not be presented elsewhere as seven days of growth. */
    fun weeklyFollowerDelta(stats: ProfileStats, history: List<HistorySample>): Long? {
        if (!stats.followersKnown || stats.followersCount < 0L || stats.syncedAt <= 0L) return null
        val zone = ZoneId.systemDefault()
        val target = Instant.ofEpochMilli(stats.syncedAt).atZone(zone).toLocalDate().minusDays(7)
        val anchor = history.filter {
            !it.estimated && it.followersKnown && it.followers >= 0L && it.timestamp in 1..stats.syncedAt &&
                Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == target
        }.maxByOrNull { it.timestamp } ?: return null
        return stats.followersCount - anchor.followers
    }

    fun relativeStandout(analytics: PostAnalytics): PostSummary? = analytics.recentPosts
        .distinctBy(PostSummary::url)
        .mapNotNull { post ->
            val baseline = PostAgeComparison.baseline(post, analytics) ?: return@mapNotNull null
            val views = baseline.ratio(post, PostSummary::views) ?: 0.0
            val engagements = baseline.ratio(post, PostSummary::engagements) ?: 0.0
            // Tiny counts can produce large ratios. Require some observed
            // substance in whichever metric supports the comparison.
            val strength = maxOf(
                views.takeIf { post.views >= 100L } ?: 0.0,
                engagements.takeIf { post.engagements >= 5L } ?: 0.0,
            )
            (post to strength).takeIf { strength >= 1.5 }
        }
        .sortedWith(compareByDescending<Pair<PostSummary, Double>> { it.second }
            .thenByDescending { it.first.timestamp }.thenBy { it.first.url })
        .firstOrNull()?.first

    fun followerCard(
        stats: ProfileStats,
        history: List<HistorySample>,
        strings: BriefStrings,
        now: Long = System.currentTimeMillis(),
    ): BriefCard? {
        if (!stats.followersKnown || stats.followersCount < 0L || stats.syncedAt !in 1..now ||
            now - stats.syncedAt > 2 * DAY_MS
        ) return null
        val zone = ZoneId.systemDefault()
        val end = Instant.ofEpochMilli(stats.syncedAt).atZone(zone).toLocalDate()
        val anchors = history.filter {
            !it.estimated && it.followersKnown && it.followers >= 0L && it.timestamp in 1..stats.syncedAt
        }.map { sample ->
            sample to Instant.ofEpochMilli(sample.timestamp).atZone(zone).toLocalDate()
        }.filter { (_, date) -> ChronoUnit.DAYS.between(date, end) in 1L..8L }
        // Prefer a week-long comparison. For a shorter history, say exactly
        // which dates were observed instead of calling it a complete week.
        val (anchor, start) = anchors.minWithOrNull(
            compareBy<Pair<HistorySample, java.time.LocalDate>> {
                abs(ChronoUnit.DAYS.between(it.second, end) - 7L)
            }.thenByDescending { it.first.timestamp },
        ) ?: return null
        val days = ChronoUnit.DAYS.between(start, end)
        val delta = stats.followersCount - anchor.followers
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(strings.locale)
        return BriefCard(
            id = "follower-context-$end",
            type = BriefCardType.FOLLOWER_CONTEXT,
            title = strings.text(if (days >= 6) R.string.brief_context_followers_week else R.string.brief_context_followers_recorded),
            body = strings.text(
                R.string.brief_context_followers_body,
                format.format(start), format.format(end),
                (if (delta > 0) "+" else "") + strings.number(delta),
                strings.number(anchor.followers), strings.number(stats.followersCount),
            ) + if (stats.followerContext?.overlapsDecline(stats, history, now) == true) " " + strings.text(R.string.follower_event_caveat) else "",
            metrics = listOf(
                BriefMetric(strings.text(R.string.brief_stat_net_change), (if (delta > 0) "+" else "") + strings.number(delta)),
                BriefMetric(format.format(start), strings.number(anchor.followers)),
                BriefMetric(format.format(end), strings.number(stats.followersCount)),
            ),
            score = 70,
            rankSignals = BriefRankSignals(contextRelevance = 0.8, timeRelevance = 0.65, occurredAt = stats.syncedAt),
        )
    }

    fun postCard(
        analytics: PostAnalytics?,
        strings: BriefStrings,
        now: Long = System.currentTimeMillis(),
    ): BriefCard? {
        analytics ?: return null
        if (analytics.cachedAt !in 1..now || now - analytics.cachedAt > 2 * DAY_MS) return null
        val posts = analytics.recentPosts.filter { post ->
            PostAgeComparison.valid(post) && post.authorUserName.equals(analytics.userName, ignoreCase = true) &&
                post.timestamp in 1..analytics.cachedAt &&
                analytics.cachedAt - post.timestamp <= analytics.windowDays * DAY_MS
        }.distinctBy(PostSummary::url)
        if (posts.size < 2 || posts.none { it.views > 0L || it.engagements > 0L }) return null
        return BriefCard(
            id = "post-context",
            type = BriefCardType.POST_CONTEXT,
            title = strings.text(R.string.brief_context_posts_title),
            body = strings.text(
                R.string.brief_context_posts_body,
                strings.number(posts.size.toLong()), strings.number(posts.sumOf { it.views }),
                strings.number(posts.sumOf { it.engagements }),
            ) + if (analytics.isSampled) " " + strings.text(R.string.brief_context_posts_sampled) else "",
            metrics = listOf(
                BriefMetric(strings.text(R.string.brief_metric_impressions), strings.number(posts.sumOf { it.views })),
                BriefMetric(strings.text(R.string.brief_metric_engagements), strings.number(posts.sumOf { it.engagements })),
                BriefMetric(strings.text(R.string.brief_stat_posts), strings.number(posts.size.toLong())),
            ),
            score = 72,
            rankSignals = BriefRankSignals(contextRelevance = 0.8, timeRelevance = 0.65, occurredAt = analytics.cachedAt),
        )
    }
}

/** Explicit post identity keeps the preview and its explanation aligned. */
internal fun resolveBriefPost(card: BriefCard, analytics: PostAnalytics?): PostSummary? {
    analytics ?: return null
    if (card.type !in setOf(BriefCardType.POST, BriefCardType.WORST_POST)) return null
    if (card.actionData.isNotBlank()) {
        return (analytics.recentPosts + listOfNotNull(analytics.best, analytics.worst))
            .firstOrNull { it.url == card.actionData }
    }
    // Older saved cards and debug scenarios predate explicit post identity.
    return if (card.type == BriefCardType.POST) analytics.best else analytics.worst
}
