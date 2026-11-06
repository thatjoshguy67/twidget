package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** A reference binds a figure to its metric and original tweet; the model cannot change either. */
internal data class BriefReviewMeasurement(val source: String, val metric: String, val value: BigDecimal,
    val postedAt: Long = 0, val peerCount: Int = 0)

internal object BriefReviewMeasurements {
    const val INSTRUCTION = "The app displays all recorded statistics in separate measurement cards. Prefer qualitative explanations of the wording and media. For numerical performance claims in possibleExplanation or reason, use the exact supplied measurementReferences token, " +
        "such as {{stat:performance:views}} or {{stat:p0:likes}}. The app supplies its figure, unit and source attribution. " +
        "Use {{observation:e0}} to quote a supplied observation as a complete sentence. Never type, swap or calculate performance figures yourself. " +
        "Do not put measurement tokens in action, rewrite or question. Product names and version numbers visible in media are ordinary context, not performance measurements. "
    private val token = Regex("\\{\\{(?:stat:([a-z0-9_]+):([a-z_]+)|observation:(e\\d+))\\}\\}")
    private val number = "[+-]?\\d+(?:[.,]\\d+)*(?:[ \\u00a0\\u202f]\\d{3}(?!\\d))*"
    private val units = "interacci(?:ó|ons) públi(?:ca|ques)|interacci(?:ó|ons)|visualitzaci(?:ó|ons)|seguidors?|respost(?:a|es)|m[’']agrada|tuits? citats?|hores?|impressi(?:ó|ons)|interacci(?:ón|ones) públicas?|interacci(?:ón|ones)|visualizaci(?:ón|ones)|vistas?|seguidor(?:es)?|respuestas?|me gusta|retuits?|republicaci(?:ón|ones)|tuits? citados?|citas?|horas?|impresi(?:ón|ones)|public interactions|interactions publiques|öffentliche[n]? interaktionen|quote tweets?|tweets? cités?|zitat-tweets?|" +
        "engagements?|interactions?|interaktionen|impressions?|views?|vues?|aufrufe[n]?|likes?|j[’']aime|replies|reply|réponses?|antworten?|" +
        "retweets?|reposts?|hours?|heures?|stunden?|followers?|abonnés?|follower"
    private val after = Regex("($number)[ \\u00a0\\u202f-]+($units)(?![\\p{L}\\p{M}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val before = Regex("\\b($units)\\s*[:=]\\s*($number)", RegexOption.IGNORE_CASE)
    private val percentage = Regex("($number)[ \\u00a0\\u202f]*%")

    fun facts(input: BriefCoachInput): Map<String, BriefReviewMeasurement> = buildMap {
        fun post(source: String, post: PostSummary) {
            fun add(metric: String, value: Long) { put("$source:$metric", BriefReviewMeasurement(source, metric, value.toBigDecimal(), post.timestamp)) }
            if (post.viewsKnown) add("views", post.views)
            add("likes", post.likes); add("replies", post.replies); add("retweets", post.reposts); add("quotes", post.quotes)
            add("interactions", BriefReviewEvidence.interactions(post))
            input.observedAt?.takeIf { post.timestamp in 1..it }?.let { add("age_hours", (it - post.timestamp) / 3_600_000L) }
            if (post.viewsKnown && post.views > 0) put("$source:rate", BriefReviewMeasurement(source, "rate",
                BriefReviewEvidence.interactions(post).toBigDecimal().multiply(BigDecimal(100))
                    .divide(post.views.toBigDecimal(), 1, RoundingMode.HALF_UP), post.timestamp))
        }
        input.reviewEvidence?.post?.let { post("performance", it) }
        input.history.take(4).forEachIndexed { index, value -> post("p$index", value) }
        input.reviewEvidence?.let { evidence -> evidence.medianPeerViews?.let {
            put("history_comparison:views", BriefReviewMeasurement("history_comparison", "views", it.toBigDecimal(), peerCount = evidence.peers.size))
        } }
    }

    fun references(input: BriefCoachInput, facts: Map<String, BriefReviewMeasurement>): Map<String, String> = buildMap {
        facts.forEach { (key, fact) -> put("{{stat:$key}}", "${fact.source}: ${fact.metric}=${fact.value.toPlainString()}") }
        input.report.measurements.take(6).forEachIndexed { index, text -> put("{{observation:e$index}}", text) }
    }

    private fun metric(unit: String): String = when {
        Regex("view|vue|aufruf|impression|visualitz|impressi|visualizaci|vista|impresi").containsMatchIn(unit.lowercase(Locale.ROOT)) -> "views"
        Regex("quote|cité|zitat|citado|citats|cita").containsMatchIn(unit.lowercase(Locale.ROOT)) -> "quotes"
        Regex("retweet|repost|retuit|republicaci").containsMatchIn(unit.lowercase(Locale.ROOT)) -> "retweets"
        Regex("like|aime|me gusta|agrada").containsMatchIn(unit.lowercase(Locale.ROOT)) -> "likes"
        Regex("repl|réponse|antwort|respuesta|respost").containsMatchIn(unit.lowercase(Locale.ROOT)) -> "replies"
        Regex("hour|heure|stunde|hora|hores").containsMatchIn(unit.lowercase(Locale.ROOT)) -> "age_hours"
        Regex("follower|abonné|seguidor").containsMatchIn(unit.lowercase(Locale.ROOT)) -> "followers"
        else -> "interactions"
    }

    private fun quantities(text: String): List<Pair<String, String>> = buildList {
        after.findAll(text).forEach { add(metric(it.groupValues[2]) to it.groupValues[1]) }
        before.findAll(text).forEach { add(metric(it.groupValues[1]) to it.groupValues[2]) }
        percentage.findAll(text).forEach { add("rate" to "${it.groupValues[1]}%") }
    }

    fun accepts(feedback: BriefCoachFeedback, packet: BriefCoachPacket): Boolean {
        val locale = Locale.forLanguageTag(packet.language)
        fun narrative(value: String, citations: List<BriefCoachCitation>): Boolean {
            var valid = true
            val bare = token.replace(value) { match ->
                val observation = match.groupValues[3]
                if (observation.isNotEmpty()) {
                    if (packet.citations.none { it.id == observation }) valid = false
                } else {
                    val source = match.groupValues[1]
                    val key = "$source:${match.groupValues[2]}"
                    if (key !in packet.measurements || (source.matches(Regex("p\\d+")) && citations.none { it.id == source })) valid = false
                }
                ""
            }
            if (!valid || bare.contains("{{") || bare.contains("}}")) return false
            val claims = quantities(bare)
            if (claims.isEmpty()) return true // Pixel 9, version 1.3.1 and other non-statistical context remain unrestricted.
            if (!packet.allowLegacyMeasurements) return false
            val source = when {
                Regex("median|médian|mediana", RegexOption.IGNORE_CASE).containsMatchIn(bare) -> "history_comparison"
                Regex("previous|earlier|similar|historique|précédent|ähnlich|früher|anterior|previ[oa]|hist[óò]ric", RegexOption.IGNORE_CASE).containsMatchIn(bare) ->
                    citations.filter { it.id.matches(Regex("p\\d+")) }.singleOrNull()?.id ?: return false
                else -> "performance"
            }
            return claims.all { (metric, value) ->
                val fact = packet.measurements["$source:$metric"]
                val expected = fact?.let {
                    NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it.value) + if (metric == "rate") "%" else ""
                } ?: return@all false
                BriefNumericEvidence.accepts(value, expected, locale)
            }
        }
        if (!narrative(feedback.possibleExplanation, feedback.suggestions.flatMap { it.citations })) return false
        return feedback.suggestions.all {
            narrative(it.reason, it.citations) && !it.action.contains("{{") && it.rewrite?.contains("{{") != true
        } && feedback.question?.contains("{{") != true
    }

    fun render(value: String, input: BriefCoachInput, strings: BriefStrings): String {
        val facts = facts(input)
        return token.replace(value) { match ->
            match.groupValues[3].takeIf(String::isNotEmpty)?.let { id ->
                return@replace input.report.measurements.getOrNull(id.drop(1).toInt()) ?: ""
            }
            val fact = facts["${match.groupValues[1]}:${match.groupValues[2]}"] ?: return@replace ""
            val count = fact.value.toLong()
            val number = strings.number(count)
            val phrase = when (fact.metric) {
                "views" -> strings.quantityText(R.plurals.review_preview_view_count, BriefStrings.quantity(count), number)
                "interactions" -> strings.quantityText(R.plurals.review_preview_interaction_count, BriefStrings.quantity(count), number)
                "rate" -> strings.text(R.string.review_fact_rate, NumberFormat.getPercentInstance(strings.locale).apply {
                    maximumFractionDigits = 1
                }.format(fact.value.movePointLeft(2)))
                else -> strings.quantityText(when (fact.metric) {
                    "likes" -> R.plurals.review_fact_likes
                    "replies" -> R.plurals.review_fact_replies
                    "retweets" -> R.plurals.review_fact_retweets
                    "quotes" -> R.plurals.review_fact_quotes
                    else -> R.plurals.review_fact_hours
                }, BriefStrings.quantity(count), number)
            }
            when (fact.source) {
                "performance" -> if (fact.metric == "age_hours") strings.text(R.string.review_fact_age, phrase) else phrase
                "history_comparison" -> strings.text(R.string.review_fact_median, phrase, strings.number(fact.peerCount.toLong()))
                else -> strings.text(R.string.review_fact_history, phrase,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, strings.locale).format(Date(fact.postedAt)))
            }
        }
    }
}
