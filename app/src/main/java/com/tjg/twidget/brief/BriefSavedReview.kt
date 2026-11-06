package com.tjg.twidget.brief

import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostObservation
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.core.NetworkResponseParsers
import org.json.JSONArray
import org.json.JSONObject

internal data class BriefSavedReview(val subject: BriefReviewSubject, val result: BriefCoachGeneration.Success,
    val savedAt: Long, val checkedAt: Long = savedAt)

/** Advice only; resolve citations again against the current matching draft revision when restoring it. */
internal object BriefCoachResultCodec {
    fun encode(result: BriefCoachGeneration.Success): JSONObject = JSONObject().put("version", 1)
        .put("cloud", result.cloud).put("completeText", result.completeText)
        .put("inspectedMediaIds", JSONArray(result.inspectedMediaIds.toList()))
        .put("feedback", JSONObject().put("possibleExplanation", result.feedback.possibleExplanation)
            .put("question", result.feedback.question ?: "").put("panelIds", JSONArray(result.feedback.panelIds))
            .put("suggestions", JSONArray(result.feedback.suggestions.map { suggestion ->
                JSONObject().put("action", suggestion.action).put("reason", suggestion.reason).put("quote", suggestion.quote)
                    .put("evidenceIds", JSONArray(suggestion.citations.map(BriefCoachCitation::id)))
            })))

    fun decode(root: JSONObject, input: BriefCoachInput): BriefCoachGeneration.Success {
        require(root.getInt("version") == 1)
        val ids = root.getJSONArray("inspectedMediaIds").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
        val cloud = root.getBoolean("cloud")
        val packet = input.packet(attachedMediaIds = ids, cloud = cloud).copy(allowLegacyMeasurements = true)
        return BriefCoachGeneration.Success(requireNotNull(BriefCoachAi.parse(root.getJSONObject("feedback").toString(), packet)),
            cloud, ids, root.getBoolean("completeText"))
    }
}

internal data class BriefCachedDraftReview(val result: BriefCoachGeneration.Success, val savedAt: Long)

/** Public posted content and its original evidence only. Draft text, image bytes and keys are never archived here. */
internal object BriefSavedReviewCodec {
    fun encode(review: BriefSavedReview): JSONObject {
        val subject = review.subject
        require(subject.post != null && subject.draft == null && subject.target.draftId == null)
        val input = subject.input
        val report = input.report
        val evidence = requireNotNull(input.reviewEvidence)
        val feedback = review.result.feedback
        return JSONObject().put("version", 1).put("savedAt", review.savedAt).put("checkedAt", review.checkedAt)
            .put("post", AnalyticsClient.serializePost(subject.post))
            .put("language", input.language).put("text", input.text).put("complete", input.complete)
            .put("observedAt", input.observedAt).put("history", posts(input.history))
            .put("reviewCitations", JSONArray(input.reviewCitations.map { JSONObject().put("id", it.id).put("text", it.text) }))
            .put("report", JSONObject().put("evidence", JSONArray(report.evidence))
                .put("factors", JSONArray(report.possibleFactors)).put("steps", JSONArray(report.nextSteps))
                .put("limits", JSONArray(report.limitations)).put("measurements", JSONArray(report.measurements))
                .put("examples", posts(report.examples)))
            .put("evidence", JSONObject().put("peers", posts(evidence.peers))
                .put("observations", JSONArray(evidence.observations.map { point ->
                    JSONObject().put("at", point.at).put("views", point.views ?: JSONObject.NULL)
                        .put("likes", point.likes).put("replies", point.replies).put("reposts", point.reposts).put("quotes", point.quotes)
                })))
            .put("cloud", review.result.cloud).put("completeText", review.result.completeText)
            .put("inspectedMediaIds", JSONArray(review.result.inspectedMediaIds.toList()))
            .put("feedback", JSONObject().put("possibleExplanation", feedback.possibleExplanation)
                .put("question", feedback.question ?: "").put("panelIds", JSONArray(feedback.panelIds))
                .put("suggestions", JSONArray(feedback.suggestions.map { suggestion ->
                    JSONObject().put("action", suggestion.action).put("reason", suggestion.reason).put("quote", suggestion.quote)
                        .put("evidenceIds", JSONArray(suggestion.citations.map(BriefCoachCitation::id)))
                })))
    }

    fun decode(root: JSONObject, account: String): BriefSavedReview {
        require(root.getInt("version") == 1)
        val post = requireNotNull(NetworkResponseParsers.parseBridgePost(root.getJSONObject("post")))
        require(BriefPublishedReviewStore.postId(post.url) != null)
        val report = root.getJSONObject("report")
        val observedAt = root.getLong("observedAt")
        val rawEvidence = root.getJSONObject("evidence")
        val evidence = BriefReviewEvidence(post, readPosts(rawEvidence.getJSONArray("peers")),
            rawEvidence.getJSONArray("observations").let { values -> (0 until values.length()).map { index ->
                val point = values.getJSONObject(index)
                PostObservation(point.getLong("at"), if (point.isNull("views")) null else point.getLong("views"),
                    point.getLong("likes"), point.getLong("replies"), point.getLong("reposts"), point.getLong("quotes"))
            } }, observedAt)
        val input = BriefCoachInput(if (post.isReply) "reply" else "published", root.getString("text"), root.getBoolean("complete"), post.media.size + post.quotedPost?.media.orEmpty().size,
            BriefCoachReport(strings(report, "evidence"), strings(report, "factors"), strings(report, "steps"), strings(report, "limits"),
                readPosts(report.getJSONArray("examples")), strings(report, "measurements")),
            readPosts(root.getJSONArray("history")), observedAt, root.getString("language"),
            media = BriefCoachAi.reviewMedia(post), quotedPost = post.quotedPost,
            reviewEvidence = evidence,
            reviewCitations = root.getJSONArray("reviewCitations").let { array -> (0 until array.length()).map { index ->
                array.getJSONObject(index).let { BriefCoachCitation(it.getString("id"), it.getString("text")) }
            } })
        val inspected = strings(root, "inspectedMediaIds").toSet()
        val packet = input.packet(attachedMediaIds = inspected, cloud = root.getBoolean("cloud")).copy(allowLegacyMeasurements = true)
        val feedback = requireNotNull(BriefCoachAi.parse(root.getJSONObject("feedback").toString(), packet))
        val savedAt = root.getLong("savedAt").also { require(it > 0) }
        return BriefSavedReview(BriefReviewSubject(BriefReviewTarget(account, postUrl = post.url), input, post = post),
            BriefCoachGeneration.Success(feedback, root.getBoolean("cloud"), inspected, root.getBoolean("completeText")), savedAt,
            root.optLong("checkedAt", savedAt).coerceAtLeast(savedAt))
    }

    private fun posts(values: List<PostSummary>) = JSONArray(values.map(AnalyticsClient::serializePost))
    private fun readPosts(values: JSONArray) = (0 until values.length()).map { requireNotNull(NetworkResponseParsers.parseBridgePost(values.getJSONObject(it))) }
    private fun strings(root: JSONObject, key: String): List<String> = root.getJSONArray(key).let { array -> (0 until array.length()).map(array::getString) }
}
