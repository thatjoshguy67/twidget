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
 * posts. Sourced from the bridge's /analytics endpoint (guest-mode timeline)
 * and cached per account.
 */
data class PostAnalytics(
    val userName: String,
    val followers: Long,
    val postsAnalyzed: Int,
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
