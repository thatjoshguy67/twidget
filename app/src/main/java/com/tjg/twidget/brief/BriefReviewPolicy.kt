package com.tjg.twidget.brief

import com.tjg.twidget.analytics.AnalyticsClient
import org.json.JSONArray
import org.json.JSONObject

/** Reviews require cloud reasoning, but never override the user's local-only choice. */
internal object BriefReviewPolicy {
    fun blocked(mode: BriefProviderMode, keyConfigured: Boolean): BriefReviewFailure? = when {
        mode == BriefProviderMode.LOCAL -> BriefReviewFailure.LOCAL_ONLY
        !keyConfigured -> BriefReviewFailure.MISSING_KEY
        else -> null
    }
    suspend fun generate(mode: BriefProviderMode, keyConfigured: Boolean,
        cloud: suspend () -> BriefCoachGeneration): BriefCoachGeneration =
        blocked(mode, keyConfigured)?.let { BriefCoachGeneration.Failure(it) } ?: cloud()
}

internal enum class BriefReviewFailure {
    LOCAL_ONLY, MISSING_KEY, AUTHENTICATION, QUOTA, NETWORK, TIMEOUT, SERVICE, INVALID_RESPONSE,
    ANALYTICS, POST_UNAVAILABLE, INVALID_REQUEST, BLOCKED, OUTPUT_LIMIT, UNKNOWN,
}

/** The first day changes fastest; older reviews still get another look when opened. */
internal object BriefReviewFreshness {
    private const val HOUR = 3_600_000L
    fun matchesLanguage(review: BriefSavedReview, language: String): Boolean =
        review.subject.input.language.equals(language, ignoreCase = true)
    fun canReuse(review: BriefSavedReview, language: String, now: Long = System.currentTimeMillis(),
        algorithmVersion: String = BriefAlgorithmContext.bundledKey): Boolean =
        review.result.algorithmVersion == algorithmVersion && matchesLanguage(review, language) && isFresh(review, now)
    fun lifetime(postedAt: Long, savedAt: Long): Long = when {
        postedAt <= 0 -> 12 * HOUR
        savedAt - postedAt < 24 * HOUR -> 4 * HOUR
        savedAt - postedAt < 7 * 24 * HOUR -> 12 * HOUR
        else -> 48 * HOUR
    }
    fun isFresh(review: BriefSavedReview, now: Long = System.currentTimeMillis()): Boolean =
        now >= review.checkedAt && now - review.checkedAt < lifetime(review.subject.post?.timestamp ?: 0, review.checkedAt)
}

/** Observation timestamps alone do not justify paying for the same analysis again. */
internal object BriefReviewEvidenceFingerprint {
    fun same(left: BriefReviewSubject, right: BriefReviewSubject): Boolean =
        left.post != null && right.post != null && fingerprint(left) == fingerprint(right)

    private fun fingerprint(subject: BriefReviewSubject): String {
        val input = subject.input
        val evidence = input.reviewEvidence
        fun posts(values: List<com.tjg.twidget.analytics.PostSummary>) = JSONArray(values
            .sortedBy { BriefPublishedReviewStore.postId(it.url) ?: it.url }.map(AnalyticsClient::serializePost))
        // Repeated identical totals do not add a growth event. Preserve every actual change.
        val changes = evidence?.observations.orEmpty().map {
            listOf(it.views, it.likes, it.replies, it.reposts, it.quotes)
        }.fold(mutableListOf<List<Long?>>()) { values, point ->
            if (values.lastOrNull() != point) values += point
            values
        }
        return JSONObject().put("post", subject.post?.let(AnalyticsClient::serializePost))
            .put("text", input.text).put("complete", input.complete)
            .put("conversation", input.conversation?.json()?.apply { remove("observedAt") } ?: JSONObject.NULL)
            .put("peerReviewNotes", JSONObject(input.peerReviewNotes.toSortedMap()))
            .put("history", posts(input.history)).put("peers", posts(evidence?.peers.orEmpty()))
            .put("changes", JSONArray(changes.map(::JSONArray))).toString()
    }
}
