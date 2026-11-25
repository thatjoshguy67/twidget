package com.tjg.twidget.analytics

import com.tjg.twidget.core.NetworkResponseParsers
import org.json.JSONObject

data class ArchiveCoverage(
    val earliestPostAt: Long = 0,
    val latestPostAt: Long = 0,
    val status: String = "not_started",
    val replyInclusive: Boolean = false,
    val postCount: Int = 0,
    val replyCount: Int = 0,
    val scannedThroughAt: Long = 0,
) {
    fun needsBackfill(now: Long): Boolean = status !in setOf("exhausted", "stalled") &&
        (scannedThroughAt <= 0 || scannedThroughAt > now - 30 * 86_400_000L)
}

/** Stored lifetime counters stay separate from the weekly timeline and daily movements. */
internal object HistoricalPostArchive {
    fun merge(weekly: PostAnalytics, root: JSONObject, account: String, userId: String, checkedAt: Long): PostAnalytics {
        requireIdentity(root, account, userId)
        val history = parse(root, account, userId)
        val current = weekly.recentPosts + weekly.recentReplies
        val recentArchive = history.filter { it.timestamp in (checkedAt - 7 * 86_400_000L)..checkedAt }
        val additional = recentArchive.filter { archived -> current.none { it.url == archived.url } }
        val updated = if (additional.isEmpty()) weekly else {
            val recent = (current + additional).filter { it.timestamp in (checkedAt - 7 * 86_400_000L)..checkedAt }
                .distinctBy { it.url }.sortedByDescending { it.timestamp }
            AnalyticsClient.analyticsFromPosts(account, weekly.followers, recent.size, true,
                recent.filterNot { it.isReply }, recent, maxOf(weekly.cachedAt, root.optLong("updatedAt")))
                .copy(banger = weekly.banger, bangerComplete = weekly.bangerComplete, bangerPostsScanned = weekly.bangerPostsScanned)
        }
        return updated.copy(historicalPosts = history, historicalCheckedAt = checkedAt, archiveCoverage = coverage(root))
            .let { it.copy(reviewPosts = ReviewPostRetention.merge(it, weekly)) }
    }

    fun forStats(weekly: PostAnalytics?): PostAnalytics? {
        if (weekly == null || weekly.historicalPosts.isEmpty()) return weekly
        val posts = (weekly.recentPosts + weekly.recentReplies + weekly.historicalPosts)
            .distinctBy(PostSummary::url).sortedByDescending(PostSummary::timestamp)
        return AnalyticsClient.analyticsFromPosts(weekly.userName, weekly.followers, posts.size, true,
            posts.filterNot(PostSummary::isReply), bestCandidates = posts, cachedAt = weekly.cachedAt)
            .copy(historicalPosts = weekly.historicalPosts, recentPosts = posts.filterNot(PostSummary::isReply),
                recentReplies = posts.filter(PostSummary::isReply), archiveCoverage = weekly.archiveCoverage)
    }

    fun coverage(root: JSONObject): ArchiveCoverage {
        val coverage = root.optJSONObject("coverage") ?: JSONObject()
        return ArchiveCoverage(coverage.optLong("earliestPostAt"), coverage.optLong("latestPostAt"),
            root.optJSONObject("backfill")?.optString("status", "not_started") ?: "not_started",
            coverage.optString("replyCoverage") == "reply_inclusive_timeline",
            root.optInt("postCount"), root.optInt("replyCount"), root.optJSONObject("backfill")?.optLong("oldestPostAt") ?: 0L)
    }

    fun requireIdentity(root: JSONObject, account: String, userId: String) {
        require(userId.isNotBlank() && root.optString("userId") == userId &&
            root.optString("userName").equals(account, true)) { "Shared analytics account identity mismatch" }
    }

    fun parse(root: JSONObject, account: String, userId: String): List<PostSummary> {
        if (userId.isBlank() || root.optString("userId") != userId ||
            !root.optString("userName").equals(account, true)) return emptyList()
        val observedAt = root.optLong("updatedAt", 0L)
        if (observedAt <= 0) return emptyList()
        val posts = root.optJSONArray("posts") ?: return emptyList()
        return (0 until posts.length()).mapNotNull { index ->
            posts.optJSONObject(index)?.let(NetworkResponseParsers::parseBridgePost)
        }.map { post -> post.copy(authorUserName = post.authorUserName.ifBlank { account },
            observedAt = post.observedAt.takeIf { it > 0 } ?: observedAt) }
            .filter { it.authorUserName.equals(account, true) && PostAgeComparison.valid(it) && it.timestamp in 1..it.observedAt }
            .distinctBy(PostSummary::url).sortedByDescending(PostSummary::timestamp).take(2000)
    }
}
