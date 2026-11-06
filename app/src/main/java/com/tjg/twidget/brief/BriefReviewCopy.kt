package com.tjg.twidget.brief

import com.tjg.twidget.R

/** Keep model routing references out of reader-facing prose, including older cached responses. */
internal object BriefReviewCopy {
    fun withoutEmDashes(feedback: BriefCoachFeedback): BriefCoachFeedback = feedback.copy(
        possibleExplanation = BriefVoicePolicy.withoutEmDashes(feedback.possibleExplanation),
        suggestions = feedback.suggestions.map { it.copy(
            action = BriefVoicePolicy.withoutEmDashes(it.action),
            reason = BriefVoicePolicy.withoutEmDashes(it.reason),
            rewrite = null,
            needsDetails = false,
        ) },
        question = feedback.question?.let(BriefVoicePolicy::withoutEmDashes),
    )

    private val reference = Regex("(?<![\\p{L}\\p{N}_])(?:[empq]\\d+|quoted_post|interaction_breakdown|history_comparison|weekly_trend)(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE)

    fun clean(feedback: BriefCoachFeedback, input: BriefCoachInput, strings: BriefStrings): BriefCoachFeedback {
        fun prose(value: String, preserveOriginalTokens: Boolean = false) = reference.replace(BriefReviewMeasurements.render(value, input, strings)) { match ->
            val id = match.value.lowercase(java.util.Locale.ROOT)
            if (preserveOriginalTokens && reference.findAll(input.text).any { it.value.equals(id, true) }) match.value
            else when {
                id.matches(Regex("m\\d+")) -> strings.text(R.string.review_copy_attachment, id.drop(1).toLongOrNull()?.plus(1) ?: 1)
                id.matches(Regex("q\\d+")) -> strings.text(R.string.review_copy_wording)
                id == "quoted_post" -> strings.text(R.string.review_quoted_tweet)
                id.matches(Regex("p\\d+")) -> strings.text(R.string.review_copy_history)
                else -> strings.text(R.string.review_copy_measurements)
            }
        }
        return withoutEmDashes(feedback.copy(
            possibleExplanation = prose(feedback.possibleExplanation),
            suggestions = feedback.suggestions.map { it.copy(action = prose(it.action), reason = prose(it.reason),
                rewrite = null, needsDetails = false) },
            question = feedback.question?.let { prose(it) },
        ))
    }
}
