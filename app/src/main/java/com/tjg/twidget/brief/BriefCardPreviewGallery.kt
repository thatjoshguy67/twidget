package com.tjg.twidget.brief

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tjg.twidget.R
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.StreakSnapshot
import com.tjg.twidget.followers.TopFollower
import com.tjg.twidget.main.AccountGoalSettings
import com.tjg.twidget.main.MilestoneMetricSnapshot
import com.tjg.twidget.main.StreakCardFactory
import com.tjg.twidget.main.StreakCardState

/** Production card views, synthetic inputs and local controls. Never mutates account or review state. */
internal object BriefCardPreviewGallery {
    fun sections(activity: Activity, base: BriefSnapshot, card: (BriefCard) -> View,
        goal: (AccountGoalSettings?, MilestoneMetricSnapshot?) -> View,
        followers: (List<TopFollower>) -> View, history: (List<HistorySample>) -> View): List<View> {
        val now = System.currentTimeMillis()
        fun sample(type: BriefCardType, title: String, metrics: List<BriefMetric> = emptyList()) =
            BriefCard("preview-${type.name}", type, title, "", 0, metrics = metrics)
        fun group(title: String, variants: List<Pair<String, () -> View>>) = group(activity, title, variants)
        return buildList {
            add(group("Posting streak", StreakCardState.entries.map { state ->
                state.name.lowercase().replace('_', ' ') to {
                    StreakCardFactory.createBrief(activity,
                        StreakSnapshot(if (state == StreakCardState.REVIVE) 0 else 6,
                            state in setOf(StreakCardState.SAFE, StreakCardState.RECORD), null,
                            longestStreak = 6, previousLongestStreak = 4), state)
                }
            }))
            add(group("Goals", listOf(
                "Set up" to { goal(null, null) },
                "Progress" to { goal(AccountGoalSettings(true, target = 1_000.0), MilestoneMetricSnapshot(640.0, listOf(400.0, 450.0, 640.0), true)) },
                "Reached" to { goal(AccountGoalSettings(true, target = 1_000.0), MilestoneMetricSnapshot(1_000.0, listOf(800.0, 900.0, 1_000.0), true)) },
                "Slowing" to { goal(AccountGoalSettings(true, target = 1_000.0), MilestoneMetricSnapshot(640.0, listOf(600.0, 630.0, 640.0), true)) },
                "Unavailable" to { goal(AccountGoalSettings(true, target = 1_000.0), MilestoneMetricSnapshot(null, emptyList(), false)) },
            )))
            add(group("Follower change", listOf(21L, -21L, 0L).map { delta ->
                (if (delta > 0) "+$delta" else "$delta") to {
                    card(sample(BriefCardType.FOLLOWER_CONTEXT, "Your week in followers", listOf(
                        BriefMetric("Net follower change", if (delta > 0) "+$delta" else "$delta"),
                        BriefMetric("19 Nov", "1,000"), BriefMetric("26 Nov", "${1_000 + delta}"))))
                }
            }))
            add(group("Post activity", listOf("Available" to {
                card(sample(BriefCardType.POST_CONTEXT, "Your recent posts", listOf(BriefMetric("Posts", "24"),
                    BriefMetric("Average views", "1,164"), BriefMetric("Interactions", "83"))))
            })))
            add(group("Follower history", listOf(1, -1).map { direction ->
                (if (direction > 0) "Growing" else "Declining") to {
                    history((0..6).map { index -> HistorySample("", 1_000L + direction * index * 4,
                        20, 6, 0, now - (6 - index) * 86_400_000L) })
                }
            }))
            add(group("Top followers", listOf("Populated" to {
                followers((1..3).map { TopFollower("preview-$it", "sample$it", "Sample creator $it", 100_000L / it, it == 1, "") })
            }, "Empty" to { followers(emptyList()) })))
            BriefInsightPreview.snapshot(BriefStrings.from(activity), base).cards.forEach { insight ->
                add(group(BriefInsightViews.title(activity, insight.type), listOf("Available" to { card(insight) },
                    "Sparse" to { card(insight.copy(chartPoints = insight.chartPoints.take(1), activityPoints = insight.activityPoints.take(1))) })))
            }
            add(group("Posting guidance", listOf("Upcoming", "Now", "Next window").map { phase ->
                phase to { card(sample(BriefCardType.POSTING_GUIDE, when (phase) {
                    "Now" -> "Your evening window is open"; "Next window" -> "Plan for tomorrow evening"; else -> "Plan for the evening"
                })) }
            }))
            val quoted = requireNotNull(BriefReviewDebugFixtures.state(activity, "quoted").subject?.post)
            val original = quoted.copy(quotedPost = null, media = emptyList())
            add(group("Tweet cards", listOf(
                "Original" to original, "Reply" to original.copy(isReply = true),
                "Previous year" to original.copy(timestamp = now - 400L * 86_400_000),
                "Quote" to quoted, "Images" to quoted.copy(quotedPost = null, media = List(3) { quoted.media.first() }),
            ).map { (label, post) -> label to {
                BriefReviewTweetCard.create(activity, "sample", post, compact = true,
                    actions = listOf(BriefReviewAction(R.string.review_view) {}))
            } }))
            add(group("Review library", listOf("Default" to { BriefReviewLibraryCard.create(activity) {} })))
            add(group("Connect ChatGPT", listOf("Disconnected" to { BriefChatGptLinkCard.create(activity, preview = true) {} })))
            add(group("Summary", listOf("Default" to { card(sample(BriefCardType.SUMMARY, "Your week at a glance")) },
                "Inactivity" to { card(sample(BriefCardType.INACTIVITY, "Time to find your rhythm again")) })))
            add(group("Tweet review states", BriefReviewDebugFixtures.scenarios(activity).filterNot { it.first.startsWith("error_") }
                .plus("error_${BriefReviewFailure.entries.first().name}" to "Error").map { (id, label) -> label to {
                    val screen = BriefReviewScreen(activity, BriefReviewCallbacks(retry = {}, info = {}, addKey = {},
                        openPost = {}, changeMode = {}, limits = {}))
                    screen.render(BriefReviewDebugFixtures.state(activity, id))
                    screen.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {}
                        override fun onViewDetachedFromWindow(view: View) { screen.dispose() }
                    })
                    screen.view
                } }))
        }
    }

    private fun group(activity: Activity, title: String, variants: List<Pair<String, () -> View>>): View =
        BriefReviewViews.column(activity).apply {
            tag = "brief_preview_group:$title"
            addView(BriefReviewViews.text(activity, title, 18f, bold = true))
            val parameters = BriefCardPreviewParameters.read(activity)
            val visible = if (parameters.allVariants) variants else listOf(variants[parameters.index(title).coerceIn(variants.indices)])
            visible.forEach { (label, create) ->
                if (variants.size > 1) addView(BriefReviewViews.text(activity, label, 12f, secondary = true), BriefReviewViews.params(activity, 12))
                val content = create()
                fun inert(view: View) {
                    view.setOnClickListener(null)
                    view.setOnTouchListener(null)
                    view.isClickable = false; view.isFocusable = false
                    if (view is ViewGroup) for (child in 0 until view.childCount) inert(view.getChildAt(child))
                }
                inert(content)
                if (content.background != null) {
                    com.tjg.twidget.ui.SeslSurfaceHighlight.markBrief(content, BriefReviewViews.dp(activity, 28).toFloat())
                    com.tjg.twidget.ui.SeslSurfaceHighlight.applyTo(content)
                }
                addView(content, BriefReviewViews.params(activity, 8))
            }
        }
}
