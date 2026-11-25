package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostSummary
import kotlin.math.max
import kotlin.math.min

/** Entry copy changes only after a successful review; performance comes from its measured snapshot. */
internal object BriefReviewPreviewCopy {
    private const val HOUR = 3_600_000L
    fun description(post: PostSummary, saved: BriefSavedReview?, strings: BriefStrings,
        now: Long = System.currentTimeMillis()): String {
        val age = relativeAge(post.timestamp, now, strings)?.let {
            strings.text(if (post.isReply) R.string.review_preview_replied else R.string.review_preview_posted, it)
        }
        val evidence = saved?.subject?.takeIf { subject ->
            subject.post?.let { BriefPublishedReviewStore.postId(post.url) != null && BriefPublishedReviewStore.postId(it.url) == BriefPublishedReviewStore.postId(post.url) &&
                (it.authorUserName.equals(post.authorUserName, true) || subject.target.username.equals(post.authorUserName, true)) && it.isReply == post.isReply } == true
        }?.input?.reviewEvidence?.copy(rankingAt = now)
        val body = if (evidence == null) strings.text(if (post.isReply) R.string.review_preview_invite_reply else R.string.review_preview_invite)
        else {
            val subject = strings.text(if (post.isReply) R.string.review_preview_subject_reply else R.string.review_preview_subject_tweet)
            val counts = if (evidence.post.viewsKnown) strings.text(R.string.review_preview_counts,
                subject, strings.quantityText(R.plurals.review_preview_view_count,
                    BriefStrings.quantity(evidence.post.views), strings.number(evidence.post.views)),
                strings.quantityText(R.plurals.review_preview_interaction_count,
                    BriefStrings.quantity(evidence.interactions), strings.number(evidence.interactions)))
            else strings.text(R.string.review_preview_interactions, subject, strings.number(evidence.interactions))
            listOf(counts, evidence.viewsRank?.let { strings.text(R.string.review_preview_rank, it, evidence.viewsRankTotal) }.orEmpty(), comparison(evidence, strings)).filter(String::isNotBlank).joinToString(" ")
        }
        return listOfNotNull(age, body).joinToString(" ")
    }

    fun relativeAge(postedAt: Long, now: Long, strings: BriefStrings): String? {
        if (postedAt <= 0 || postedAt > now) return null
        val elapsed = now - postedAt
        if (elapsed < 60_000) return strings.text(R.string.review_preview_just_now)
        val (unit, count) = when {
            elapsed < HOUR -> R.plurals.review_preview_minutes to elapsed / 60_000
            elapsed < 24 * HOUR -> R.plurals.review_preview_hours to elapsed / HOUR
            elapsed < 7 * 24 * HOUR -> R.plurals.review_preview_days to elapsed / (24 * HOUR)
            elapsed < 30 * 24 * HOUR -> R.plurals.review_preview_weeks to elapsed / (7 * 24 * HOUR)
            elapsed < 365 * 24 * HOUR -> R.plurals.review_preview_months to elapsed / (30 * 24 * HOUR)
            else -> R.plurals.review_preview_years to elapsed / (365 * 24 * HOUR)
        }
        return strings.quantityText(unit, BriefStrings.quantity(count), strings.number(count))
    }

    private fun comparison(evidence: BriefReviewEvidence, strings: BriefStrings): String {
        val post = evidence.post
        val age = evidence.observedAt - post.timestamp
        // Waiting a day on the wall clock must not mature a two-hour observation.
        if (post.timestamp <= 0 || age < 24 * HOUR) return ""
        val peers = evidence.peers.filter { peer ->
            val peerAge = evidence.observedAt - peer.timestamp
            peer.url != post.url && BriefPublishedReviewStore.postId(peer.url) != BriefPublishedReviewStore.postId(post.url) && peer.authorUserName.equals(post.authorUserName, true) && peer.isReply == post.isReply &&
                peer.media.isEmpty() == post.media.isEmpty() && (peer.quotedPost != null) == (post.quotedPost != null) &&
                peer.timestamp > 0 && peerAge >= HOUR && max(age, peerAge).toDouble() / min(age, peerAge) <= 1.5 &&
                listOf(peer.views, peer.likes, peer.replies, peer.reposts, peer.quotes).all { it >= 0 }
        }.distinctBy { BriefPublishedReviewStore.postId(it.url) ?: it.url }
        if (peers.size < 3) return ""
        fun ratio(values: List<Long>, value: Long): Double? {
            if (values.size < 3 || value < 0) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[middle].toDouble() else sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
            return median.takeIf { it > 0 }?.let { value / it }
        }
        fun direction(value: Double?): Int? = value?.let { when { it >= 1.5 -> 1; it <= 0.65 -> -1; else -> 0 } }
        val views = if (post.viewsKnown) direction(ratio(peers.filter { it.viewsKnown }.map { it.views }, post.views)) else null
        val interactions = direction(ratio(peers.map { BriefReviewEvidence.interactions(it) }, evidence.interactions))
        val label = when {
            views == 1 && interactions == 1 -> R.string.review_preview_both_higher
            views == -1 && interactions == -1 -> R.string.review_preview_both_lower
            views == 1 && interactions == -1 -> R.string.review_preview_more_views_fewer_interactions
            views == -1 && interactions == 1 -> R.string.review_preview_fewer_views_more_interactions
            views == 1 -> R.string.review_preview_higher_reach
            views == -1 -> R.string.review_preview_lower_reach
            interactions == 1 -> R.string.review_preview_more_interactions
            interactions == -1 -> R.string.review_preview_fewer_interactions
            views == 0 && interactions == 0 -> R.string.review_preview_typical
            interactions == 0 -> R.string.review_preview_typical_interactions
            else -> return ""
        }
        return strings.text(label)
    }
}
