package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import com.airbnb.lottie.LottieAnimationView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedChartPoint
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.ui.CardValueTextView
import com.tjg.twidget.ui.MetricChartView
import dev.oneuiproject.oneui.R as OneUiIconR

internal data class BriefReviewCallbacks(
    val retry: () -> Unit, val info: () -> Unit, val addKey: () -> Unit,
    val openPost: (PostSummary) -> Unit,
    val changeMode: () -> Unit = {}, val limits: (String) -> Unit = {},
)

/** Native, data-driven cards. AI selects evidence panels but never supplies their plotted numbers. */
internal class BriefReviewScreen(private val context: Context, private val callbacks: BriefReviewCallbacks) {
    val view = BriefReviewViews.column(context)
    private val original = BriefReviewViews.column(context)
    private val body = BriefReviewViews.column(context)
    private var lastSubject: BriefReviewSubject? = null
    private var spinner: LottieAnimationView? = null
    private var rocking: ObjectAnimator? = null
    private var lastRendered: BriefReviewState? = null
    init {
        view.clipChildren = false
        original.clipChildren = false
        view.addView(original); view.addView(body, BriefReviewViews.params(context, 20))
    }
    private fun dp(value: Int) = BriefReviewViews.dp(context, value)
    private fun text(value: CharSequence, size: Float = 14f, bold: Boolean = false, secondary: Boolean = false) =
        BriefReviewViews.text(context, value, size, bold, secondary)
    private fun add(value: View, top: Int = 20) { body.addView(value, BriefReviewViews.params(context, top)) }
    private fun heading(res: Int) {
        add(BriefReviewViews.label(context, context.getString(res)).apply { setPadding(dp(19), 0, dp(19), 0) })
    }
    private fun adviceHeading(res: Int) {
        add(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            addView(BriefReviewViews.icon(context, R.drawable.ic_review_ai))
            addView(text(context.getString(res)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
    }
    private fun paragraph(res: Int) { add(text(context.getString(res)).apply { setPadding(dp(19), 0, dp(19), 0) }) }
    private fun note(res: Int) { add(text(context.getString(res), 12f, secondary = true).apply { setPadding(dp(19), 0, dp(19), 0) }) }

    fun render(state: BriefReviewState) {
        if (state == lastRendered) return
        val newlyComplete = state.phase == BriefReviewPhase.COMPLETE &&
            (lastRendered?.phase == BriefReviewPhase.GENERATING || lastRendered?.savedAt != state.savedAt)
        lastRendered = state
        dispose()
        if (state.subject != lastSubject) {
            lastSubject = state.subject
            original.removeAllViews()
            state.subject?.let { subject -> original.addView(BriefReviewTweetCard.create(context,
                subject.target.username, requireNotNull(subject.post), reviewPreview = true, openOnCardTap = true,
                onClick = subject.post?.let { post -> { callbacks.openPost(post) } })) }
        }
        original.rotation = 0f
        original.elevation = 0f
        original.getChildAt(0)?.apply { rotation = 0f; elevation = 0f }
        body.removeAllViews()
        when (state.phase) {
            BriefReviewPhase.LOADING -> Unit
            BriefReviewPhase.GENERATING -> {
                loading()
                if (ValueAnimator.areAnimatorsEnabled()) original.getChildAt(0)?.let { card ->
                    card.elevation = dp(8).toFloat()
                    rocking = ObjectAnimator.ofFloat(card, View.ROTATION, -0.7f, 0.7f, -0.7f).apply {
                        duration = 3200
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
                // A refresh never hides the last successful review.
                state.result?.let { result -> state.subject?.let { feedback(it, result); footer(it, result, state.savedAt) } }
            }
            BriefReviewPhase.MISSING -> message(R.string.review_load_failed, retry = false)
            BriefReviewPhase.STALE -> message(R.string.coach_ai_stale)
            BriefReviewPhase.FAILED -> {
                failure(state.failure)
                val subject = state.subject
                if (subject != null && state.result != null) {
                    paragraph(R.string.review_previous_kept)
                    feedback(subject, state.result)
                    footer(subject, state.result, state.savedAt)
                } else subject?.input?.reviewEvidence?.let { performance(it, showObservedAt = true) }
            }
            BriefReviewPhase.COMPLETE -> {
                val subject = state.subject ?: return
                val result = state.result ?: return
                feedback(subject, result)
                footer(subject, result, state.savedAt)
                if (newlyComplete && ValueAnimator.areAnimatorsEnabled()) {
                    fadeUp(original, 0)
                    for (index in 0 until body.childCount) fadeUp(body.getChildAt(index), minOf(index + 1, 5) * 45L)
                }
            }
        }
    }

    private fun fadeUp(child: View, delay: Long) {
        child.alpha = 0f
        child.translationY = dp(18).toFloat()
        child.animate().alpha(1f).translationY(0f).setStartDelay(delay)
            .setDuration(320).setInterpolator(PathInterpolator(0.22f, 0f, 0.18f, 1f)).start()
    }

    private fun loading() {
        add(BriefReviewViews.column(context).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(170)
            setPadding(dp(19), dp(40), dp(19), dp(40))
            addView(LottieAnimationView(context).apply {
                setAnimation(R.raw.intelligence_progress_mono)
                repeatCount = -1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                spinner = this
                if (ValueAnimator.areAnimatorsEnabled()) playAnimation() else progress = 0.5f
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(text(context.getString(R.string.review_generating), 18f, bold = true).apply {
                gravity = Gravity.CENTER
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, BriefReviewViews.params(context, 20))
        }, 0)
        note(R.string.coach_ai_cloud_note)
    }

    private fun message(res: Int, retry: Boolean = true) {
        add(BriefReviewViews.card(context).apply {
            addView(text(context.getString(res)))
            if (retry) addView(BriefReviewViews.button(context, R.string.review_retry, click = callbacks.retry), BriefReviewViews.params(context, 14))
        })
    }

    private fun failure(reason: BriefReviewFailure?) {
        val failure = BriefReviewPolicy.blocked(BriefSettingsStore.provider(context), BriefSettingsStore.cloudApiKey(context).isNotBlank())
            ?: reason ?: BriefReviewFailure.UNKNOWN
        add(BriefReviewViews.card(context).apply {
            addView(text(context.getString(briefReviewFailureMessage(failure))))
            when (failure) {
                BriefReviewFailure.LOCAL_ONLY -> addView(BriefReviewViews.button(context, R.string.review_change_mode,
                    click = callbacks.changeMode), BriefReviewViews.params(context, 14))
                BriefReviewFailure.MISSING_KEY -> addView(BriefReviewViews.button(context, R.string.review_add_key,
                    click = callbacks.addKey), BriefReviewViews.params(context, 14))
                else -> {
                    addView(BriefReviewViews.button(context, R.string.review_retry, click = callbacks.retry), BriefReviewViews.params(context, 14))
                    if (failure == BriefReviewFailure.AUTHENTICATION) addView(BriefReviewViews.button(context,
                        R.string.review_update_key, false, callbacks.addKey), BriefReviewViews.params(context, 10))
                }
            }
        })
    }

    private fun feedback(subject: BriefReviewSubject, result: BriefCoachGeneration.Success) {
        val feedback = BriefReviewCopy.clean(result.feedback, subject.input, BriefStrings.from(context))
        add(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(19), 0, dp(7), 0)
            addView(text(feedback.possibleExplanation), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_review_help)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.oneui_text_primary))
                setBackgroundResource(R.drawable.brief_review_icon_ripple)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = context.getString(R.string.review_stat_info_title)
                setOnClickListener { callbacks.info() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }, 0)
        val evidence = subject.input.reviewEvidence
        evidence?.let { performance(it) }
        val panels = (result.feedback.panelIds + result.feedback.suggestions.flatMap { it.citations.map(BriefCoachCitation::id) }).toSet()
        if (evidence != null) {
            if ("history_comparison" in panels && "history_comparison" in evidence.panelIds) history(evidence)
            if ("weekly_trend" in panels && "weekly_trend" in evidence.panelIds) trend(evidence)
        }
        adviceHeading(R.string.review_next_steps_intro)
        feedback.suggestions.forEach { suggestion ->
            add(BriefReviewViews.card(context).apply {
                addView(text(suggestion.action))
                if (suggestion.quote.isNotBlank()) addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    isBaselineAligned = false
                    addView(View(context).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(context.getColor(R.color.oneui_accent)); cornerRadius = dp(3).toFloat()
                        }
                    }, LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(text("“${suggestion.quote}”", 16f).apply {
                        typeface = com.tjg.twidget.ui.TwidgetFonts.forApp(context, 600)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
                }, BriefReviewViews.params(context, 11))
                addView(text(suggestion.reason), BriefReviewViews.params(context, 11))
            })

        }
        feedback.question?.let { question ->
            heading(R.string.coach_ai_question)
            add(BriefReviewViews.card(context).apply { addView(text(question)) })
        }
    }

    private fun performance(evidence: BriefReviewEvidence, showObservedAt: Boolean = false) {
        add(BriefReviewViews.column(context).apply {
            addView(statsRow(listOf(Triple(R.string.brief_metric_impressions,
                if (evidence.post.viewsKnown) evidence.post.views else null, OneUiIconR.drawable.ic_oui_equalizer_2),
                Triple(R.string.brief_metric_engagements, evidence.interactions, OneUiIconR.drawable.ic_oui_message_outline))))
            addView(statsRow(listOf(Triple(R.string.likes, evidence.post.likes, OneUiIconR.drawable.ic_oui_heart_outline),
                Triple(R.string.review_replies, evidence.post.replies, OneUiIconR.drawable.ic_oui_message_outline))), BriefReviewViews.params(context, 10))
            addView(statsRow(listOf(Triple(R.string.brief_metric_retweets, evidence.post.reposts, OneUiIconR.drawable.ic_oui_repeat),
                Triple(R.string.brief_metric_quote_tweets, evidence.post.quotes, OneUiIconR.drawable.ic_oui_message_outline))), BriefReviewViews.params(context, 10))
        })
        if (showObservedAt) add(text(if (evidence.observedAt > 0L) context.getString(R.string.review_observed_at,
            AppLocales.formatDate(evidence.observedAt, "MMMdjm"))
            else context.getString(R.string.coach_archived_limit),
            12f, secondary = true).apply { setPadding(dp(19), 0, dp(19), 0) }, 6)
    }

    private fun statsRow(values: List<Triple<Int, Long?, Int>>): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        values.forEachIndexed { index, (label, count, icon) ->
            val value = count?.let { BriefStrings.from(context).number(it) } ?: context.getString(R.string.coach_ai_unknown)
            addView(BriefReviewViews.card(context).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                contentDescription = "${context.getString(label)}: $value"
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER
                    addView(BriefReviewViews.icon(context, icon, 16))
                    addView(text(context.getString(label), 14f, bold = true, secondary = true),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(7) })
                })
                addView(CardValueTextView(context).apply {
                    text = value
                    setTextColor(context.getColor(R.color.oneui_text_primary))
                    gravity = Gravity.CENTER
                    configure(80f, 130)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108)).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = dp(10) })
        }
    }

    private fun history(evidence: BriefReviewEvidence) {
        heading(R.string.review_history_comparison)
        add(statsRow(listOf(Triple(R.string.review_subject_views, evidence.post.views, OneUiIconR.drawable.ic_oui_equalizer_2),
            Triple(R.string.review_peer_median, evidence.medianPeerViews, OneUiIconR.drawable.ic_oui_equalizer_2))))
    }

    private fun trend(evidence: BriefReviewEvidence) {
        heading(R.string.review_trend_title)
        val points = evidence.observations.let { values ->
            if (values.size <= 7) values else (0..6).map { values[it * (values.size - 1) / 6] }
        }.map { ImportedChartPoint(AppLocales.formatDate(it.at, "MMMdjm"), it.interactions) }
        add(BriefReviewViews.card(context).apply {
            addView(MetricChartView(context).apply {
                setSeries(points)
                contentDescription = points.joinToString("; ") { "${it.label}: ${it.value}" }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(194)))
            addView(text(context.getString(R.string.review_trend_note), 12f, secondary = true), BriefReviewViews.params(context, 8))
        })
    }

    private fun footer(subject: BriefReviewSubject, result: BriefCoachGeneration.Success, savedAt: Long?) {
        add(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(19), 0, 0, 0)
            val label = savedAt?.let { context.getString(R.string.review_saved_at,
                AppLocales.formatDate(it, "MMMdjm")) }
                ?: context.getString(if (result.cloud) R.string.coach_ai_cloud_result else R.string.coach_ai_local_result)
            addView(text(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_review_help)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.oneui_text_primary))
                setBackgroundResource(R.drawable.brief_review_icon_ripple)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                contentDescription = context.getString(R.string.coach_limits)
                setOnClickListener { callbacks.limits(limitations(subject, result)) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
    }

    private fun limitations(subject: BriefReviewSubject, result: BriefCoachGeneration.Success): String {
        val limits = subject.input.report.limitations.toMutableList()
        subject.input.reviewEvidence?.observedAt?.takeIf { it > 0 }?.let {
            limits += context.getString(R.string.review_observed_at,
                AppLocales.formatDate(it, "MMMdjm"))
        }
        if (!result.completeText) limits += context.getString(R.string.coach_ai_text_excerpt)
        subject.input.media.take(4).forEachIndexed { index, ref ->
            val description = context.getString(when {
            "m$index" !in result.inspectedMediaIds -> R.string.coach_ai_media_unseen
            ref.video -> R.string.coach_ai_media_video
            else -> R.string.coach_ai_media_image
            }, index + 1, ref.entry)
            limits += if (ref.quoted) context.getString(R.string.review_quoted_media, description) else description
        }
        if (subject.input.mediaCount > 4) limits += context.getString(R.string.coach_ai_media_omitted, subject.input.mediaCount - 4)
        limits += context.getString(if (result.cloud) R.string.coach_ai_cloud_result else R.string.coach_ai_local_result)
        return limits.joinToString("\n\n")
    }

    fun dispose() {
        spinner?.cancelAnimation(); spinner = null
        rocking?.cancel(); rocking = null
        original.animate().cancel()
        for (index in 0 until body.childCount) body.getChildAt(index).animate().cancel()
        original.alpha = 1f; original.translationY = 0f
    }
}

internal fun briefReviewFailureMessage(reason: BriefReviewFailure): Int = when (reason) {
    BriefReviewFailure.LOCAL_ONLY -> R.string.review_error_local
    BriefReviewFailure.MISSING_KEY -> R.string.review_error_cloud_key
    BriefReviewFailure.AUTHENTICATION -> R.string.review_error_authentication
    BriefReviewFailure.QUOTA -> R.string.review_error_quota
    BriefReviewFailure.NETWORK -> R.string.review_error_network
    BriefReviewFailure.TIMEOUT -> R.string.review_error_timeout
    BriefReviewFailure.SERVICE -> R.string.review_error_service
    BriefReviewFailure.INVALID_RESPONSE -> R.string.review_error_invalid_response
    BriefReviewFailure.ANALYTICS -> R.string.review_error_analytics
    BriefReviewFailure.POST_UNAVAILABLE -> R.string.review_error_post_unavailable
    BriefReviewFailure.INVALID_REQUEST -> R.string.review_error_invalid_request
    BriefReviewFailure.BLOCKED -> R.string.review_error_blocked
    BriefReviewFailure.OUTPUT_LIMIT -> R.string.review_error_output_limit
    BriefReviewFailure.UNKNOWN -> R.string.review_error_cloud
}
