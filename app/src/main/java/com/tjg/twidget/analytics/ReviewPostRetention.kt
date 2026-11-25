package com.tjg.twidget.analytics

/** A rolling review cohort, independent of the provider's weekly totals. */
internal object ReviewPostRetention {
    fun merge(current: PostAnalytics, previous: PostAnalytics?): List<PostSummary> {
        val old = previous?.takeIf { it.userName.equals(current.userName, true) }
        val fresh = (current.recentPosts + current.recentReplies).map { it.copy(observedAt = current.cachedAt) }
        val retained = current.reviewPosts + current.historicalPosts + old?.reviewPosts.orEmpty() +
            (old?.recentPosts.orEmpty() + old?.recentReplies.orEmpty()).map { it.copy(observedAt = it.observedAt.takeIf { at -> at > 0 } ?: old!!.cachedAt) }
        return (fresh + retained).filter { (!it.isReply || publicInteractions(it.likes, it.replies, it.reposts, it.quotes) >= 10) && PostAgeComparison.valid(it) && it.timestamp in 1..maxOf(current.cachedAt, current.historicalCheckedAt) &&
            it.authorUserName.trimStart('@').equals(current.userName.trimStart('@'), true) }
            .distinctBy { it.url }.sortedByDescending { it.timestamp }.take(10)
    }
}
