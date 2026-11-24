package com.tjg.twidget.analytics

import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefStrings

data class PostLink(
    val display: String,
    val url: String,
)

data class PostMedia(
    val type: String,
    val url: String,
    val alt: String,
    val width: Long,
    val height: Long,
)

/** A single quoted source, kept separate from the account's own text and measurements. */
data class QuotedPost(val url: String, val text: String, val authorName: String, val authorUserName: String,
    val authorAvatar: String = "", val media: List<PostMedia> = emptyList(), val textComplete: Boolean = true, val timestamp: Long = 0L)

/** One post in the best/worst pair. */
data class PostSummary(
    val url: String,
    val text: String,
    val views: Long,
    val likes: Long,
    val replies: Long,
    val reposts: Long,
    val quotes: Long,
    val engagements: Long,
    val timestamp: Long,
    val createdAt: String,
    val authorName: String,
    val authorUserName: String,
    val authorAvatar: String,
    val links: List<PostLink> = emptyList(),
    val media: List<PostMedia> = emptyList(),
    /** Null in older caches or when the provider only supplied a shortened excerpt. */
    val fullText: String? = null,
    val isReply: Boolean = false,
    val viewsKnown: Boolean = true,
    val likesKnown: Boolean = true,
    val repostsKnown: Boolean = true,
    val repliesKnown: Boolean = true,
    val quotesKnown: Boolean = true,
    val quotedPost: QuotedPost? = null,
)

/**
 * Reach and engagement over the recent timeline, plus the 7-day best/worst
 * posts. Sourced directly from FxTwitter or TwitterAPIs, or from the selected
 * bridge, and cached per provider and account.
 */
data class PostAnalytics(
    val userName: String,
    val followers: Long,
    val postsAnalyzed: Int,
    /** Timeline rows inspected before replies/reposts/old posts were removed. */
    val statusesInspected: Int = postsAnalyzed,
    /** True when the bounded FxTwitter walk stopped before the timeline ended. */
    val isSampled: Boolean = false,
    val windowDays: Int,
    val totalViews: Long,
    val avgViews: Double,
    val medianViews: Double,
    val avgViewsPerFollower: Double,
    val totalEngagements: Long,
    val avgEngagements: Double,
    val medianEngagements: Double,
    val avgEngagementsPerFollower: Double,
    val engagementRate: Double,
    val best: PostSummary?,
    val worst: PostSummary?,
    val banger: PostSummary? = null,
    val bangerComplete: Boolean = false,
    val bangerPostsScanned: Int = 0,
    val cachedAt: Long,
    val medianLikes: Double = 0.0,
    val medianReplies: Double = 0.0,
    val medianShares: Double = 0.0,
    /** Recent original posts retained locally for evidence-based Brief guidance. */
    val recentPosts: List<PostSummary> = emptyList(),
    /** Kept separately so replies never change original-post totals or benchmarks. */
    val recentReplies: List<PostSummary> = emptyList(),
)

enum class TweetPerformanceDirection { STRONG, QUIET }

data class TweetPerformanceExplanation(
    val title: String,
    val body: String,
    val confidence: Int,
)

/** Evidence-only comparisons against this account’s other posts of similar observed age. */
object TweetPerformanceExplainer {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun quietTweetEligible(post: PostSummary?, analytics: PostAnalytics, now: Long = System.currentTimeMillis()): Boolean =
        post != null && analytics.cachedAt <= now &&
            (PostAgeComparison.age(post, analytics) ?: 0L) >= DAY_MS &&
            PostAgeComparison.baseline(post, analytics)?.let { baseline ->
                val views = baseline.ratio(post, PostSummary::views)
                val engagements = baseline.ratio(post, PostSummary::engagements)
                views != null && engagements != null && views <= 0.65 && engagements <= 0.75
            } == true

    fun explain(
        post: PostSummary,
        analytics: PostAnalytics,
        direction: TweetPerformanceDirection,
        strings: BriefStrings,
    ): TweetPerformanceExplanation {
        val baseline = PostAgeComparison.baseline(post, analytics)
        val engagementRatio = baseline?.ratio(post, PostSummary::engagements)
        val viewRatio = baseline?.ratio(post, PostSummary::views)
        val replyRatio = baseline?.ratio(post, PostSummary::replies)
        val likeRatio = baseline?.ratio(post, PostSummary::likes)
        val quoteRatio = baseline?.ratio(post, PostSummary::quotes)
        val retweetRatio = baseline?.ratio(post, PostSummary::reposts)

        val observations = when (direction) {
            TweetPerformanceDirection.STRONG -> buildList {
                if ((engagementRatio ?: 0.0) >= 1.25) add(R.string.tweet_performance_strong_engagements)
                if ((viewRatio ?: 0.0) >= 1.5) add(R.string.tweet_performance_strong_views)
                if ((likeRatio ?: 0.0) >= 1.5 && post.likes >= 2) add(R.string.tweet_performance_strong_likes)
                if ((quoteRatio ?: 0.0) >= 1.5 && post.quotes >= 2) add(R.string.tweet_performance_strong_quotes)
                if ((retweetRatio ?: 0.0) >= 1.5 && post.reposts >= 2) add(R.string.tweet_performance_strong_retweets)
                if ((replyRatio ?: 0.0) >= 1.5 && post.replies >= 2) add(R.string.tweet_performance_strong_replies)
            }
            TweetPerformanceDirection.QUIET -> buildList {
                if ((engagementRatio ?: Double.NaN) in 0.0..0.75) add(R.string.tweet_performance_quiet_engagements)
                if ((viewRatio ?: Double.NaN) in 0.0..0.65) add(R.string.tweet_performance_quiet_views)
                if ((likeRatio ?: Double.NaN) in 0.0..0.65) add(R.string.tweet_performance_quiet_likes)
                if ((quoteRatio ?: Double.NaN) in 0.0..0.65) add(R.string.tweet_performance_quiet_quotes)
                if ((retweetRatio ?: Double.NaN) in 0.0..0.65) add(R.string.tweet_performance_quiet_retweets)
                if ((replyRatio ?: Double.NaN) in 0.0..0.65) add(R.string.tweet_performance_quiet_replies)
            }
        }
        val body = observations.distinct().take(2).joinToString(" ", transform = strings::text).ifBlank {
            strings.text(
                if (baseline == null) {
                    R.string.tweet_performance_insufficient_data
                } else if (direction == TweetPerformanceDirection.STRONG) {
                    R.string.tweet_performance_strong_unexplained
                } else {
                    R.string.tweet_performance_quiet_unexplained
                },
            )
        }
        val confidence = when {
            baseline == null || analytics.isSampled || observations.isEmpty() -> 1
            observations.size >= 2 && baseline.peers.size >= 5 -> 3
            else -> 2
        }
        return TweetPerformanceExplanation(
            title = strings.text(
                if (direction == TweetPerformanceDirection.STRONG) {
                    R.string.tweet_performance_strong_title
                } else {
                    R.string.tweet_performance_quiet_title
                },
            ),
            body = body,
            confidence = confidence,
        )
    }
}
