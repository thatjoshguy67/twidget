package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostObservation
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.analytics.publicInteractions
import org.json.JSONArray
import org.json.JSONObject

/** The model selects relevant panels; all their values come from recorded evidence. */
internal data class BriefReviewEvidence(val post: PostSummary, val peers: List<PostSummary>,
    val observations: List<PostObservation>, val observedAt: Long, val rankingPosts: List<PostSummary> = peers) {
    private fun rankKey(value: PostSummary) = BriefPublishedReviewStore.postId(value.url) ?: value.url
    private val rankingPeers get() = rankingPosts.filter { rankKey(it) != rankKey(post) && it.isReply == post.isReply && it.viewsKnown }
        .distinctBy(::rankKey).sortedByDescending { it.timestamp }.take(9)
    val viewsRankTotal: Int get() = rankingPeers.size + 1
    val viewsRank: Int? get() = if (post.viewsKnown) 1 + rankingPeers.count { it.views > post.views } else null
    val interactions: Long get() = interactions(post)
    val medianPeerViews: Long? get() = peers.takeIf { it.size >= 3 }?.map(PostSummary::views)?.sorted()?.let {
        if (it.size % 2 == 1) it[it.size / 2] else it[it.size / 2 - 1] + (it[it.size / 2] - it[it.size / 2 - 1]) / 2
    }
    val panelIds: Set<String> get() = buildSet {
        add("performance")
        add("interaction_breakdown")
        if (post.viewsKnown && medianPeerViews != null) add("history_comparison")
        if (observations.size >= 3) add("weekly_trend")
    }

    fun json(): JSONObject = JSONObject().put("availablePanelIds", JSONArray(panelIds.toList()))
        .put("observedAt", observedAt).put("views", if (post.viewsKnown) post.views else JSONObject.NULL)
        .put("publicInteractions", interactions).put("likes", post.likes).put("replies", post.replies)
        .put("retweets", post.reposts).put("quoteTweets", post.quotes)
        .put("sameKindSameFormatAgePeerCount", peers.size).put("peerMedianViews", medianPeerViews ?: JSONObject.NULL)
        .put("recordedTotalsLastSevenDays", JSONArray(observations.map {
            JSONObject().put("at", it.at).put("publicInteractions", it.interactions)
        })).put("trendCaveat", "Recorded cumulative totals at actual observation times, not daily gains. Missing days are unknown.")

    fun citations(strings: BriefStrings): List<BriefCoachCitation> = buildList {
        add(BriefCoachCitation("performance", strings.text(R.string.review_counts_evidence,
            if (post.viewsKnown) strings.number(post.views) else strings.text(R.string.coach_ai_unknown), strings.number(interactions))))
        add(BriefCoachCitation("interaction_breakdown", strings.text(R.string.review_breakdown_evidence,
            strings.number(post.likes), strings.number(post.replies), strings.number(post.reposts), strings.number(post.quotes))))
        medianPeerViews?.let { median -> add(BriefCoachCitation("history_comparison",
            strings.text(R.string.coach_peer_views, peers.size, strings.number(median)))) }
        if ("weekly_trend" in panelIds) add(BriefCoachCitation("weekly_trend",
            strings.text(R.string.review_trend_evidence, observations.size,
                strings.number(observations.first().interactions), strings.number(observations.last().interactions))))
    }

    companion object {
        fun interactions(post: PostSummary): Long = publicInteractions(post.likes, post.replies, post.reposts, post.quotes)
        fun create(post: PostSummary, analytics: PostAnalytics, observations: List<PostObservation>): BriefReviewEvidence {
            val ranking = (analytics.reviewPosts + analytics.recentPosts + analytics.recentReplies)
                .filter { it.isReply == post.isReply && it.authorUserName.equals(analytics.userName, true) && com.tjg.twidget.analytics.PostAgeComparison.valid(it) }
                .distinctBy(PostSummary::url).sortedByDescending(PostSummary::timestamp).take(10)
            // A retained all-time winner is not necessarily re-fetched with the weekly timeline.
            // Its cached totals cannot be assigned today's observation time or a growth chart.
            if (com.tjg.twidget.analytics.PostAgeComparison.age(post, analytics) == null ||
                (post.observedAt <= 0 && analytics.cachedAt - post.timestamp > analytics.windowDays * 86_400_000L))
                return BriefReviewEvidence(post, emptyList(), emptyList(), post.observedAt, ranking)
            val peers = BriefPostCoach.comparisonPeers(post, analytics).takeIf {
                (com.tjg.twidget.analytics.PostAgeComparison.age(post, analytics) ?: 0L) >= 24 * 60 * 60_000L
            }.orEmpty()
            return BriefReviewEvidence(post, peers, observations.filter {
                it.at in maxOf(post.timestamp, analytics.cachedAt - 7 * 24 * 60 * 60_000L)..analytics.cachedAt
            }, post.observedAt.takeIf { it > 0 } ?: analytics.cachedAt, ranking)
        }
    }
}
