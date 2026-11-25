package com.tjg.twidget.brief

import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAgeComparison
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduledPost
import java.text.NumberFormat
import java.util.Locale

/** Local observations and editorial hypotheses are intentionally separate and never AI-rewritten. */
internal data class BriefCoachReport(
    val evidence: List<String>,
    val possibleFactors: List<String>,
    val nextSteps: List<String>,
    val limitations: List<String>,
    val examples: List<PostSummary> = emptyList(),
    val measurements: List<String> = evidence,
)

internal object BriefPostCoach {
    private const val HOUR = 60 * 60 * 1000L
    private const val DAY = 24 * HOUR
    private const val MIN_PEERS = 3
    private const val MIN_RATE_VIEWS = 100L
    private val urls = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val words = Regex("[\\p{L}\\p{N}]{4,}")
    private val stopWords = setOf(
        "this", "that", "with", "from", "have", "your", "just", "about", "what", "when", "will", "been", "more",
        "diese", "dieser", "einen", "eine", "nicht", "auch", "avec", "pour", "dans", "cette", "vous", "plus",
    )
    private val vagueOpening = Regex(
        "^(?:finally|just|so|well|endlich|enfin)\\b[^.!?\\n]{0,65}\\b(?:this|it|das|dies|ça|cela)\\s*[.!?]?$",
        RegexOption.IGNORE_CASE,
    )
    private val broadQuestion = Regex(
        "(?:^|[.!?\\n]\\s*)(thoughts|any thoughts|what do you think|meinungen|was denkt ihr|des avis|votre avis)\\s*\\?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun readyDrafts(posts: List<ScheduledPost>, username: String): List<ScheduledPost> = posts
        .filter { it.deletedAt == null && it.status == ScheduleStatus.DRAFT && sameAccount(account(it), username) }
        .filter { it.thread.any { item -> item.text.isNotBlank() || item.media.isNotEmpty() } }
        .sortedByDescending(ScheduledPost::updatedAt)

    fun reviewablePosts(analytics: PostAnalytics?, username: String, now: Long): List<PostSummary> {
        if (!fresh(analytics, username, now)) return emptyList()
        analytics!!
        val originals = (analytics.reviewPosts.ifEmpty { analytics.recentPosts }).filterNot(PostSummary::isReply)
        val replies = (analytics.reviewPosts.ifEmpty { analytics.recentReplies }).filter { it.isReply && interactions(it) >= 10.0 }
        return (originals.sortedByDescending(PostSummary::timestamp) + replies.sortedByDescending(::interactions))
            .filter { ownValid(it, username) && PostAgeComparison.age(it, analytics) != null }
            .distinctBy(PostSummary::url).sortedByDescending(PostSummary::timestamp)
    }

    /** Explicit dashboard selections also include young/quiet replies and the older all-time winner.
     * Keep these out of automatic Brief eligibility and weekly benchmarks. */
    fun reviewTargets(analytics: PostAnalytics?, username: String, now: Long): List<PostSummary> {
        if (!fresh(analytics, username, now)) return emptyList()
        analytics!!
        return (reviewablePosts(analytics, username, now) + analytics.recentReplies + analytics.reviewPosts + analytics.historicalPosts + listOfNotNull(analytics.best, analytics.worst, analytics.banger))
            .filter { ownValid(it, username) && it.timestamp in 1..maxOf(analytics.cachedAt, analytics.historicalCheckedAt) }
            .distinctBy(PostSummary::url)
    }

    fun draft(
        draft: ScheduledPost,
        analytics: PostAnalytics?,
        strings: BriefStrings,
        now: Long,
    ): BriefCoachReport {
        val opening = draft.thread.firstOrNull()
        val text = opening?.text.orEmpty()
        val evidence = mutableListOf(strings.text(R.string.coach_draft_evidence, draft.thread.size,
            draft.thread.sumOf { it.media.size }))
        val measurements = evidence.toMutableList()
        val findings = contentFindings(text, opening?.media?.isNotEmpty() == true, strings)
        evidence += findings.map(Finding::evidence)
        val factors = findings.map(Finding::factor).toMutableList()
        val steps = findings.map(Finding::step).toMutableList()
        val limits = mutableListOf(strings.text(R.string.coach_draft_limit))
        var examples = emptyList<PostSummary>()
        val history = analytics?.takeIf { fresh(it, account(draft), now) }?.recentPosts.orEmpty()
            .filter { !it.isReply && ownValid(it, account(draft)) &&
                analytics != null && (PostAgeComparison.age(it, analytics) ?: 0L) >= DAY }
            .distinctBy(PostSummary::url)
        val duplicate = history.firstOrNull { it.fullText != null && normalized(it.fullText) == normalized(text) && text.length >= 25 }
        if (duplicate != null) {
            evidence += strings.text(R.string.coach_duplicate_evidence)
            factors += strings.text(R.string.coach_duplicate_factor)
            steps += strings.text(R.string.coach_duplicate_step)
            examples = listOf(duplicate)
        } else {
            val terms = tokens(text)
            val similar = history.filter { post -> terms.intersect(tokens(post.fullText ?: post.text)).size >= 2 }
                .sortedByDescending(PostSummary::timestamp).take(6)
            if (similar.size >= MIN_PEERS) {
                evidence += strings.text(R.string.coach_similar_evidence, similar.size)
                steps += strings.text(R.string.coach_similar_step)
                examples = similar.take(2)
            }
        }
        // A cohort is selected by age and format, never by its performance. This is
        // association in this account's observed history, not an algorithmic media bonus.
        val cohort = analytics?.let { PostAgeComparison.cohort(history.filter(PostSummary::viewsKnown), it) }.orEmpty()
        val withMedia = cohort.filter { it.media.isNotEmpty() }
        val textOnly = cohort.filter { it.media.isEmpty() }
        if (withMedia.size >= MIN_PEERS && textOnly.size >= MIN_PEERS) {
            val mediaViews = median(withMedia.map { it.views.toDouble() })
            val textViews = median(textOnly.map { it.views.toDouble() })
            val mediaWins = mediaViews > textViews
            if (minOf(mediaViews, textViews) > 0 && maxOf(mediaViews, textViews) / minOf(mediaViews, textViews) >= 1.5) {
                val observation = strings.text(R.string.coach_format_evidence, withMedia.size, textOnly.size,
                    strings.number(mediaViews.toLong()), strings.number(textViews.toLong()))
                evidence += observation
                measurements += observation
                factors += strings.text(R.string.coach_format_factor)
                steps += strings.text(if (mediaWins) R.string.coach_format_media_step else R.string.coach_format_text_step)
            }
        }
        if (history.size < MIN_PEERS) limits += strings.text(R.string.coach_history_limit)
        if (analytics?.isSampled == true) limits += strings.text(R.string.coach_sample_limit)
        if (steps.isEmpty()) steps += strings.text(R.string.coach_draft_default_step)
        return BriefCoachReport(evidence.distinct(), factors.distinct(), steps.distinct().take(3), limits, examples, measurements)
    }

    fun review(post: PostSummary, analytics: PostAnalytics, strings: BriefStrings, now: Long): BriefCoachReport? {
        if (reviewTargets(analytics, analytics.userName, now).none { it == post }) return null
        val age = analytics.cachedAt - post.timestamp
        val archived = age > analytics.windowDays * DAY
        val evidence = mutableListOf(if (archived) {
            strings.text(if (post.viewsKnown) R.string.coach_archived_counts else R.string.coach_archived_counts_without_views,
                *if (post.viewsKnown) arrayOf(strings.number(post.views), decimal(interactions(post), strings))
                else arrayOf(decimal(interactions(post), strings)))
        } else if (post.viewsKnown) {
            strings.text(R.string.coach_post_counts, strings.number(post.views),
                decimal(interactions(post), strings), strings.number(age / HOUR))
        } else {
            strings.text(R.string.coach_post_counts_without_views, decimal(interactions(post), strings), strings.number(age / HOUR))
        })
        val factors = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val limits = mutableListOf(strings.text(R.string.coach_post_limit))
        if (archived) limits += strings.text(R.string.coach_archived_limit)
        val peers = comparisonPeers(post, analytics)
        if (age >= DAY && peers.size >= MIN_PEERS && post.viewsKnown) {
            val peerViews = median(peers.map { it.views.toDouble() })
            evidence += strings.text(R.string.coach_peer_views, peers.size, strings.number(peerViews.toLong()))
            val ratePeers = peers.filter { it.views >= MIN_RATE_VIEWS }
            val rate = rate(post)
            val peerRate = ratePeers.takeIf { it.size >= MIN_PEERS }?.let { median(it.mapNotNull(::rate)) }
            if (rate != null && peerRate != null) {
                evidence += strings.text(R.string.coach_peer_rate, decimal(rate, strings), decimal(peerRate, strings), ratePeers.size)
            }
            val viewRatio = peerViews.takeIf { it > 0 }?.let { post.views / it }
            when {
                viewRatio != null && viewRatio <= 0.65 && rate != null && peerRate != null && rate >= peerRate * 0.8 -> {
                    factors += strings.text(R.string.coach_exposure_factor)
                    steps += strings.text(R.string.coach_exposure_step)
                }
                rate != null && peerRate != null && peerRate > 0 && rate < peerRate * 0.65 -> {
                    factors += strings.text(R.string.coach_response_factor)
                    steps += strings.text(R.string.coach_response_step)
                }
                viewRatio != null && viewRatio >= 1.5 -> {
                    factors += strings.text(R.string.coach_reach_factor)
                    steps += strings.text(R.string.coach_reach_step)
                }
            }
        } else {
            limits += strings.text(when {
                !post.viewsKnown -> R.string.coach_unknown_views_limit
                age < DAY -> R.string.coach_early_limit
                else -> R.string.coach_peers_limit
            })
        }
        val measurements = evidence.toList()
        if (post.fullText != null) {
            val findings = contentFindings(post.fullText, post.media.isNotEmpty(), strings)
            evidence += findings.map(Finding::evidence)
            factors += findings.map(Finding::factor)
            steps += findings.map(Finding::step)
        } else {
            limits += strings.text(R.string.coach_excerpt_limit)
        }
        if (post.isReply) {
            limits += strings.text(R.string.coach_reply_limit)
            steps += strings.text(R.string.coach_reply_step)
        }
        if (analytics.isSampled) limits += strings.text(R.string.coach_sample_limit)
        if (steps.isEmpty()) steps += strings.text(R.string.coach_review_default_step)
        return BriefCoachReport(evidence.distinct(), factors.distinct(), steps.distinct().take(3), limits,
            peers.sortedByDescending(PostSummary::timestamp).take(2), measurements)
    }

    internal fun comparisonPeers(post: PostSummary, analytics: PostAnalytics): List<PostSummary> =
        (analytics.reviewPosts.ifEmpty { if (post.isReply) analytics.recentReplies else analytics.recentPosts }).filter {
            (PostAgeComparison.age(post, analytics) ?: Long.MAX_VALUE) <= analytics.windowDays * DAY &&
                (PostAgeComparison.age(it, analytics) ?: Long.MAX_VALUE) <= analytics.windowDays * DAY &&
                it.url != post.url && it.isReply == post.isReply && ownValid(it, analytics.userName) &&
                it.media.isEmpty() == post.media.isEmpty() && (it.quotedPost != null) == (post.quotedPost != null) && it.viewsKnown && PostAgeComparison.similarAge(post, it, analytics)
        }.distinctBy(PostSummary::url)

    internal fun account(draft: ScheduledPost): String = draft.accountId?.takeIf(String::isNotBlank) ?: draft.accountUsername

    private data class Finding(val evidence: String, val factor: String, val step: String)

    private fun contentFindings(text: String, hasMedia: Boolean, strings: BriefStrings): List<Finding> {
        val clean = text.trim().take(10_000)
        val firstSentence = clean.lineSequence().firstOrNull().orEmpty().split(Regex("(?<=[.!?])\\s+")).first()
        val results = mutableListOf<Finding>()
        fun add(evidence: Int, factor: Int, step: Int, vararg args: Any) {
            results += Finding(strings.text(evidence, *args), strings.text(factor), strings.text(step))
        }
        if (clean.isBlank() && hasMedia) add(R.string.coach_media_only_evidence, R.string.coach_media_only_factor, R.string.coach_media_only_step)
        else if (clean.isNotBlank() && urls.replace(clean, "").trim().isEmpty()) {
            add(R.string.coach_link_evidence, R.string.coach_link_factor, R.string.coach_link_step)
        } else if (vagueOpening.matches(firstSentence)) {
            add(R.string.coach_opening_evidence, R.string.coach_opening_factor, R.string.coach_opening_step, firstSentence)
        }
        broadQuestion.find(clean)?.groupValues?.get(1)?.let { question ->
            add(R.string.coach_question_evidence, R.string.coach_question_factor, R.string.coach_question_step, question)
        }
        val firstParagraph = clean.substringBefore("\n")
        if (firstParagraph.length > 220 && results.size < 2) {
            add(R.string.coach_paragraph_evidence, R.string.coach_paragraph_factor, R.string.coach_paragraph_step,
                strings.number(firstParagraph.length.toLong()))
        }
        return results.take(2)
    }

    private fun fresh(analytics: PostAnalytics?, username: String, now: Long): Boolean = analytics != null &&
        sameAccount(analytics.userName, username) && maxOf(analytics.cachedAt, analytics.historicalCheckedAt) in 1..now && now - maxOf(analytics.cachedAt, analytics.historicalCheckedAt) <= 2 * DAY

    private fun ownValid(post: PostSummary, username: String): Boolean = PostAgeComparison.valid(post) &&
        sameAccount(post.authorUserName, username)

    private fun sameAccount(left: String, right: String): Boolean = left.trim().trimStart('@')
        .equals(right.trim().trimStart('@'), ignoreCase = true)

    private fun normalized(text: String): String = text.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    private fun tokens(text: String): Set<String> = words.findAll(urls.replace(text.take(10_000), ""))
        .map { it.value.lowercase(Locale.ROOT) }.filterNot { it in stopWords }.toSet()

    private fun interactions(post: PostSummary): Double = post.likes.toDouble() + post.replies + post.reposts + post.quotes
    private fun rate(post: PostSummary): Double? = post.views.takeIf { post.viewsKnown && it >= MIN_RATE_VIEWS }
        ?.let { interactions(post) * 100.0 / it }
    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else sorted[sorted.size / 2 - 1] / 2 + sorted[sorted.size / 2] / 2
    }
    private fun decimal(value: Double, strings: BriefStrings): String = NumberFormat.getNumberInstance(strings.locale)
        .apply { maximumFractionDigits = 2 }.format(value)
}
