package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAgeComparison
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId

/** All measurements are calculated locally. AI may explain them but cannot supply missing telemetry. */
internal object BriefInsightPolicy {
    private const val DAY = 86_400_000L
    private fun posts(data: PostAnalytics?, now: Long): List<PostSummary> {
        data ?: return emptyList()
        if (data.cachedAt !in 1..now || now - data.cachedAt > 2 * DAY) return emptyList()
        return data.recentPosts.filter {
            PostAgeComparison.valid(it) && !it.isReply && it.viewsKnown &&
                it.authorUserName.equals(data.userName, true) && it.timestamp in 1..data.cachedAt &&
                data.cachedAt - it.timestamp <= minOf(data.windowDays, 7) * DAY
        }.distinctBy { it.url }
    }
    private fun metric(label: Int, value: Long, strings: BriefStrings) = BriefMetric(strings.text(label), strings.number(value))
    private fun signed(value: Long, strings: BriefStrings) = (if (value > 0) "+" else "") + strings.number(value)
    private fun card(type: BriefCardType, title: Int, body: String, metrics: List<BriefMetric>, at: Long, strings: BriefStrings) = BriefCard(
        id = "insight-${type.name.lowercase(java.util.Locale.ROOT)}", type = type,
        title = strings.text(title), body = body, metrics = metrics, score = 76,
        rankSignals = BriefRankSignals(contextRelevance = 0.85, timeRelevance = 0.7, occurredAt = at),
    )

    fun conversation(data: PostAnalytics?, strings: BriefStrings, now: Long): BriefCard? {
        val included = posts(data, now)
        if (included.size < 3) return null
        val views = included.sumOf { it.views }
        val replies = included.sumOf { it.replies }
        val quotes = included.sumOf { it.quotes }
        if (views < 300 || replies + quotes < 3) return null
        val rate = NumberFormat.getNumberInstance(strings.locale).apply { maximumFractionDigits = 1 }
            .format((replies + quotes).toDouble() * 1000 / views)
        return card(BriefCardType.CONVERSATION_PULSE, R.string.brief_pulse_title,
            strings.text(R.string.brief_pulse_body, strings.number(included.size.toLong()), strings.number(views)),
            listOf(BriefMetric(strings.text(R.string.brief_pulse_rate), rate),
                metric(R.string.brief_insight_replies, replies, strings), metric(R.string.brief_insight_quotes, quotes, strings)), data!!.cachedAt, strings).copy(chartPoints = listOf(
                BriefChartPoint(strings.text(R.string.brief_insight_replies), replies),
                BriefChartPoint(strings.text(R.string.brief_insight_quotes), quotes)))
    }

    private fun format(post: PostSummary): Int? = when {
        post.quotedPost != null -> null
        post.media.isEmpty() -> R.string.brief_format_text
        post.media.any { it.type.lowercase(java.util.Locale.ROOT) in setOf("video", "gif", "animated_gif") } -> R.string.brief_format_video
        post.media.all { it.type.lowercase(java.util.Locale.ROOT) in setOf("image", "photo") } -> R.string.brief_format_image
        else -> null
    }
    private fun median(posts: List<PostSummary>): Long {
        val values = posts.map { it.views }.sorted()
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else values[mid - 1] + (values[mid] - values[mid - 1]) / 2
    }
    fun contentPattern(data: PostAnalytics?, strings: BriefStrings, now: Long): BriefCard? {
        data ?: return null
        // Choose the cohort by age alone, before looking at formats or performance.
        val cohort = PostAgeComparison.cohort(posts(data, now).filter { format(it) != null }, data)
        val groups = cohort.groupBy { format(it)!! }.filterValues { it.size >= 3 }
            .entries.sortedWith(compareByDescending<Map.Entry<Int, List<PostSummary>>> { median(it.value) }.thenBy { it.key })
        if (groups.size < 2) return null
        val high = groups[0]; val low = groups[1]
        val highMedian = median(high.value); val lowMedian = median(low.value)
        if (lowMedian < 100 || highMedian.toDouble() / lowMedian < 1.25) return null
        return card(BriefCardType.CONTENT_PATTERN, R.string.brief_pattern_title,
            strings.text(R.string.brief_pattern_body, strings.text(high.key), strings.text(low.key)),
            listOf(BriefMetric(strings.text(R.string.brief_pattern_median, strings.text(high.key)), strings.number(highMedian)),
                BriefMetric(strings.text(R.string.brief_pattern_median, strings.text(low.key)), strings.number(lowMedian)),
                metric(R.string.brief_insight_compared, (high.value.size + low.value.size).toLong(), strings)), data.cachedAt, strings).copy(chartPoints = listOf(
                BriefChartPoint(strings.text(high.key), highMedian, samples = high.value.size),
                BriefChartPoint(strings.text(low.key), lowMedian, samples = low.value.size)))
    }

    fun experiment(data: PostAnalytics?, strings: BriefStrings, now: Long): BriefCard? {
        val pattern = contentPattern(data, strings, now) ?: return null
        return card(BriefCardType.CONTENT_EXPERIMENT, R.string.brief_experiment_title,
            strings.text(R.string.brief_experiment_body, pattern.metrics.first().label.substringBefore(" ·")),
            pattern.metrics, pattern.rankSignals.occurredAt, strings).copy(action = BriefCardAction.COMPOSE_TWEET, chartPoints = pattern.chartPoints)
    }

    fun topic(data: PostAnalytics?, strings: BriefStrings, now: Long): BriefCard? {
        data ?: return null
        val cohort = PostAgeComparison.cohort(posts(data, now), data)
        if (cohort.size < 6) return null
        fun tags(post: PostSummary) = Regex("(?<![\\p{L}\\p{N}_])#([\\p{L}\\p{N}_]{2,40})")
            .findAll(post.text).map { "#" + it.groupValues[1].lowercase(java.util.Locale.ROOT) }.toSet()
        val tagSets = cohort.associate { it.url to tags(it) }
        val counts = tagSets.values.flatten().groupingBy { it }.eachCount()
        // Select by frequency, not whichever hashtag happens to produce the largest ratio.
        val tag = counts.filterValues { it >= 3 }.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key ?: return null
        val tagged = cohort.filter { tag in tagSets.getValue(it.url) }
        val others = cohort.filter { tag !in tagSets.getValue(it.url) }
        if (others.size < 3) return null
        val baseline = median(others)
        if (baseline < 100 || median(tagged).toDouble() / baseline < 1.25) return null
        return card(BriefCardType.TOPIC_SPOTLIGHT, R.string.brief_topic_title,
            strings.text(R.string.brief_topic_body, tag),
            listOf(BriefMetric(strings.text(R.string.brief_pattern_median, tag), strings.number(median(tagged))),
                metric(R.string.brief_topic_other_views, baseline, strings),
                metric(R.string.brief_topic_tagged_posts, tagged.size.toLong(), strings)), data.cachedAt, strings).copy(chartPoints = listOf(
                BriefChartPoint(tag, median(tagged), samples = tagged.size),
                BriefChartPoint(strings.text(R.string.brief_chart_other_tweets), baseline, samples = others.size)))
    }

    private fun followerPoints(stats: ProfileStats, history: List<HistorySample>, days: Long, strings: BriefStrings): List<BriefChartPoint> {
        val zone = ZoneId.systemDefault()
        val end = Instant.ofEpochMilli(stats.syncedAt).atZone(zone).toLocalDate()
        val samples = history.filter { !it.estimated && it.followersKnown && it.followers >= 0 && it.timestamp in 1..stats.syncedAt }
            .groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM", strings.locale)
        return (days downTo 0L).map { offset ->
            val date = end.minusDays(offset)
            val value = if (offset == 0L) stats.followersCount else samples[date]?.maxByOrNull { it.timestamp }?.followers
            BriefChartPoint(dateFormat.format(date), value, date.atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }

    fun momentum(stats: ProfileStats, history: List<HistorySample>, strings: BriefStrings, now: Long): BriefCard? {
        if (!stats.followersKnown || stats.followersCount < 0 || stats.syncedAt !in 1..now || now - stats.syncedAt > 2 * DAY) return null
        val week = BriefContextPolicy.weeklyFollowerDelta(stats, history) ?: return null
        val zone = ZoneId.systemDefault()
        val end = Instant.ofEpochMilli(stats.syncedAt).atZone(zone).toLocalDate()
        val old = history.filter { !it.estimated && it.followersKnown && it.followers >= 0 && it.timestamp in 1..stats.syncedAt &&
            Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == end.minusDays(14) }.maxByOrNull { it.timestamp } ?: return null
        val previous = stats.followersCount - week - old.followers
        return card(BriefCardType.AUDIENCE_MOMENTUM, R.string.brief_momentum_title,
            strings.text(R.string.brief_momentum_body, signed(week, strings), signed(previous, strings)),
            listOf(BriefMetric(strings.text(R.string.brief_insight_this_week), signed(week, strings)),
                BriefMetric(strings.text(R.string.brief_insight_previous_week), signed(previous, strings)),
                metric(R.string.brief_insight_followers, stats.followersCount, strings)), stats.syncedAt, strings).copy(chartPoints = followerPoints(stats, history, 14, strings))
    }

    fun recap(stats: ProfileStats, history: List<HistorySample>, data: PostAnalytics?, strings: BriefStrings, now: Long): BriefCard? {
        val included = posts(data, now)
        if (included.size < 3 || data == null || !data.userName.equals(stats.userName, true) || data.windowDays < 7 || stats.syncedAt !in 1..now || now - stats.syncedAt > 2 * DAY) return null
        val week = BriefContextPolicy.weeklyFollowerDelta(stats, history) ?: return null
        // Keep profile and timeline observations on the same local day.
        val zone = ZoneId.systemDefault()
        if (Instant.ofEpochMilli(stats.syncedAt).atZone(zone).toLocalDate() != Instant.ofEpochMilli(data.cachedAt).atZone(zone).toLocalDate()) return null
        return card(BriefCardType.WEEKLY_RECAP, R.string.brief_recap_title,
            strings.text(R.string.brief_recap_body),
            listOf(BriefMetric(strings.text(R.string.brief_insight_follower_change), signed(week, strings)),
                metric(R.string.brief_insight_included_posts, included.size.toLong(), strings),
                metric(R.string.brief_insight_typical_views, median(included), strings)), data.cachedAt, strings).copy(
                chartPoints = followerPoints(stats, history, 7, strings),
                activityPoints = (7L downTo 0L).map { offset ->
                    val end = Instant.ofEpochMilli(data.cachedAt).atZone(zone).toLocalDate()
                    val date = end.minusDays(offset)
                    BriefChartPoint(java.time.format.DateTimeFormatter.ofPattern("d MMM", strings.locale).format(date),
                        included.count { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() == date }.toLong(),
                        date.atStartOfDay(zone).toInstant().toEpochMilli())
                })
    }
}
