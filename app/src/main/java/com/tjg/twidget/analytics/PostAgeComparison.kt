package com.tjg.twidget.analytics

/** Comparisons of observed totals, never estimates of a post's eventual performance. */
object PostAgeComparison {
    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS
    const val MIN_PEERS = 2

    class Baseline internal constructor(val peers: List<PostSummary>) {
        /** A zero baseline does not support a meaningful multiplicative comparison. */
        fun ratio(post: PostSummary, metric: (PostSummary) -> Long): Double? {
            val values = peers.map(metric).sorted()
            val middle = values.size / 2
            val median = if (values.size % 2 == 1) values[middle].toDouble()
            else values[middle - 1] / 2.0 + values[middle] / 2.0
            return median.takeIf { it > 0.0 }?.let { metric(post).toDouble() / it }
        }
    }

    // cachedAt is the observation time. Advancing the clock must not make a
    // cached two-hour count look like a measured two-day count.
    fun age(post: PostSummary, analytics: PostAnalytics): Long? {
        if (maxOf(analytics.cachedAt, analytics.historicalCheckedAt) <= 0 || post.observedAt > maxOf(analytics.cachedAt, analytics.historicalCheckedAt)) return null
        val observed = post.observedAt.takeIf { it > 0 } ?: analytics.cachedAt
        return if (observed > 0 && post.timestamp in 1..observed)
            (observed - post.timestamp).takeIf { it >= HOUR_MS }
        else null
    }

    fun similarAge(left: PostSummary, right: PostSummary, analytics: PostAnalytics): Boolean {
        val a = age(left, analytics) ?: return false
        val b = age(right, analytics) ?: return false
        return maxOf(a, b).toDouble() / minOf(a, b) <= 1.5
    }

    /** Pick the largest age-compatible group using age alone, never performance. */
    fun cohort(posts: List<PostSummary>, analytics: PostAnalytics): List<PostSummary> {
        val ordered = posts.filter { valid(it) && age(it, analytics) != null }
            .distinctBy(PostSummary::url)
            .sortedBy { age(it, analytics) }
        return ordered.indices.map { start ->
            ordered.drop(start).takeWhile { similarAge(ordered[start], it, analytics) }
        }.maxByOrNull { it.size }.orEmpty()
    }

    fun valid(post: PostSummary): Boolean = post.url.isNotBlank() && post.authorUserName.isNotBlank() &&
        listOf(post.views, post.engagements, post.likes, post.replies, post.reposts, post.quotes).all { it >= 0L }

    fun baseline(post: PostSummary, analytics: PostAnalytics): Baseline? {
        // recentPosts are original posts. A best-post reply outside this set
        // can still be celebrated for its absolute counts, without comparing
        // it to a different kind of post. Legacy caches have no fair baseline.
        if (!valid(post) || !post.authorUserName.equals(analytics.userName, ignoreCase = true) ||
            analytics.recentPosts.none { it.url == post.url }
        ) return null
        val peers = analytics.recentPosts.asSequence()
            .filter { it.url.isNotBlank() && it.url != post.url }
            .filter { it.authorUserName.equals(post.authorUserName, ignoreCase = true) }
            .filter(::valid)
            .distinctBy(PostSummary::url)
            .filter { similarAge(post, it, analytics) }
            .toList()
        return peers.takeIf { it.size >= MIN_PEERS }?.let(::Baseline)
    }
}
