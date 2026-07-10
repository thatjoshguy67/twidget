package com.tjg.twidget

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
)

/**
 * Reach and engagement over the recent timeline, plus the 7-day best/worst
 * posts. Sourced directly from FxTwitter or from the selected bridge and cached
 * per provider and account.
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
    val cachedAt: Long,
)
