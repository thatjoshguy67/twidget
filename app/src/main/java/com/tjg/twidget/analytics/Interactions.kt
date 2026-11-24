package com.tjg.twidget.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

enum class InteractionMetric { LIKES, REPOSTS, REPLIES, SHARES, QUOTES, BOOKMARKS, VIEWS }

data class InteractionCounts(
    val likes: Long? = null, val reposts: Long? = null, val replies: Long? = null,
    val shares: Long? = null, val quotes: Long? = null, val bookmarks: Long? = null, val views: Long? = null,
) {
    fun value(metric: InteractionMetric): Long? = when (metric) {
        InteractionMetric.LIKES -> likes
        InteractionMetric.REPOSTS -> reposts
        InteractionMetric.REPLIES -> replies
        InteractionMetric.SHARES -> shares
        InteractionMetric.QUOTES -> quotes
        InteractionMetric.BOOKMARKS -> bookmarks
        InteractionMetric.VIEWS -> views
    }
    fun merge(other: InteractionCounts, maximum: Boolean): InteractionCounts {
        fun choose(a: Long?, b: Long?): Long? = when {
            a == null -> b
            b == null -> a
            maximum -> maxOf(a, b)
            else -> b
        }
        return InteractionCounts(choose(likes, other.likes), choose(reposts, other.reposts),
            choose(replies, other.replies), choose(shares, other.shares), choose(quotes, other.quotes),
            choose(bookmarks, other.bookmarks), choose(views, other.views))
    }
}

data class InteractionDay(val date: LocalDate, val counts: InteractionCounts)
data class InteractionPost(val id: String, val createdAt: Long, val capturedAt: Long, val isReply: Boolean, val counts: InteractionCounts)
data class InteractionData(val daily: List<InteractionDay> = emptyList(), val posts: List<InteractionPost> = emptyList())
data class InteractionPoint(val date: LocalDate, val value: Long?)
data class InteractionSlice(val metric: InteractionMetric, val count: Long?, val percent: Int?)
data class InteractionSplit(val originals: Int, val replies: Int, val originalPercent: Int, val replyPercent: Int)

object InteractionAggregation {
    /** Post fallback is by post date: lifetime counts observed so far, not activity earned that day. */
    fun series(data: InteractionData, metric: InteractionMetric, days: Int, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<InteractionPoint> {
        require(days > 0)
        val daily = data.daily.associateBy { it.date }
        val posts = data.posts.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(ZoneOffset.UTC).toLocalDate() }
        return (days - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val row = daily[date]
            val value = if (row != null) row.counts.value(metric) else posts[date]?.let { group ->
                val values = group.map { it.counts.value(metric) }
                if (values.any { it == null }) null else values.filterNotNull().sum()
            }
            InteractionPoint(date, value)
        }
    }
    fun total(points: List<InteractionPoint>): Long? = points.mapNotNull { it.value }.let { if (it.isEmpty()) null else it.sum() }
    fun totals(data: InteractionData, days: Int, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Map<InteractionMetric, Long?> =
        InteractionMetric.entries.associateWith { total(series(data, it, days, today)) }
    fun mix(data: InteractionData, days: Int, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): List<InteractionSlice> {
        val metrics = listOf(InteractionMetric.LIKES, InteractionMetric.REPOSTS, InteractionMetric.REPLIES, InteractionMetric.QUOTES)
        val metricSeries = metrics.map { series(data, it, days, today) }
        val values = metricSeries.map(::total)
        // A mix needs comparable coverage on every observed day. A missing
        // category cannot be presented as a zero-sized segment.
        val comparable = (0 until days).all { index ->
            val present = metricSeries.count { it[index].value != null }
            present == 0 || present == metrics.size
        }
        val shares = if (comparable && values.all { it != null }) percentages(values.filterNotNull()) else null
        return metrics.indices.map { InteractionSlice(metrics[it], values[it], shares?.get(it)) }
    }
    fun split(data: InteractionData, days: Int, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): InteractionSplit {
        val first = today.minusDays((days - 1).toLong())
        val posts = data.posts.filter {
            val date = Instant.ofEpochMilli(it.createdAt).atZone(ZoneOffset.UTC).toLocalDate()
            !date.isBefore(first) && !date.isAfter(today)
        }
        val originals = posts.count { !it.isReply }
        val replies = posts.size - originals
        val percentages = percentages(listOf(originals.toLong(), replies.toLong()))
        return InteractionSplit(originals, replies, percentages[0], percentages[1])
    }
    private fun percentages(values: List<Long>): List<Int> {
        val total = values.sum().toDouble()
        if (total <= 0.0) return List(values.size) { 0 }
        val exact = values.map { it * 100.0 / total }
        val result = exact.map { it.toInt() }.toMutableList()
        val order = exact.indices.sortedByDescending { exact[it] - result[it] }
        repeat(100 - result.sum()) { result[order[it]]++ }
        return result
    }
}

fun mergeInteractions(existing: InteractionData, incoming: InteractionData): InteractionData {
    val days = existing.daily.associateBy { it.date }.toMutableMap()
    incoming.daily.forEach { row -> days[row.date] = days[row.date]?.let { row.copy(counts = it.counts.merge(row.counts, false)) } ?: row }
    val posts = existing.posts.associateBy { it.id }.toMutableMap()
    incoming.posts.forEach { row ->
        val old = posts[row.id]
        posts[row.id] = if (old == null) row else (if (row.capturedAt >= old.capturedAt) row else old)
            .copy(counts = old.counts.merge(row.counts, true))
    }
    return InteractionData(days.values.sortedBy { it.date }.takeLast(366), posts.values.sortedBy { it.createdAt }.takeLast(2000))
}
