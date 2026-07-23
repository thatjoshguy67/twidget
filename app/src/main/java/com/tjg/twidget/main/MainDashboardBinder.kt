package com.tjg.twidget.main

import android.animation.LayoutTransition
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ActivityClient
import com.tjg.twidget.analytics.AnalyticsBlendPolicy
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.BlendedAnalytics
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.XAnalyticsMovement
import com.tjg.twidget.data.AccountAverageSeries
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersCardBinder
import com.tjg.twidget.ui.MetricChartView
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// Two grid footprints only: half-width and full-width. Charts are
// full-width cards with extra height.
internal enum class DashboardCardSize(val span: Int, val heightDp: Int) {
    HALF(1, 140),
    FULL(2, 156),
    CHART(2, 260),
    TOP_FOLLOWERS(2, 430),
    POST(2, 360),
}

internal enum class DashboardCardType(val id: String, val labelRes: Int, val size: DashboardCardSize) {
    FOLLOWER_RATIO("follower_ratio", R.string.follower_ratio, DashboardCardSize.HALF),
    POST_RATE("post_rate", R.string.post_rate, DashboardCardSize.HALF),
    LIKES_PER_POST("likes_per_post", R.string.likes_per_post, DashboardCardSize.HALF),
    ENGAGEMENT_RATE("engagement_rate", R.string.engagement_rate, DashboardCardSize.HALF),
    AVG_VIEWS("avg_views", R.string.avg_views, DashboardCardSize.HALF),
    TOTAL_VIEWS("total_views", R.string.total_views, DashboardCardSize.HALF),
    AVG_ENGAGEMENTS("avg_engagements", R.string.avg_engagements, DashboardCardSize.HALF),
    MEDIAN_ENGAGEMENTS("median_engagements", R.string.median_engagements, DashboardCardSize.HALF),
    X_IMPRESSIONS("x_impressions", R.string.x_impressions, DashboardCardSize.HALF),
    X_ENGAGEMENTS("x_engagements", R.string.x_engagements, DashboardCardSize.HALF),
    X_PROFILE_VISITS("x_profile_visits", R.string.x_profile_visits, DashboardCardSize.HALF),
    X_LIKES_RECEIVED("x_likes_received", R.string.x_likes_received, DashboardCardSize.HALF),
    MILESTONE("milestone", R.string.milestone_progress, DashboardCardSize.FULL),
    DAILY_STREAK("daily_streak", R.string.daily_streak, DashboardCardSize.HALF),
    GROWTH_PACE("growth_pace", R.string.growth_pace, DashboardCardSize.HALF),
    BEST_DAY("best_day", R.string.best_recent_day, DashboardCardSize.HALF),
    MOMENTUM("momentum", R.string.momentum, DashboardCardSize.HALF),
    AUDIENCE_BALANCE("audience_balance", R.string.audience_balance, DashboardCardSize.HALF),
    ACCOUNT_HEALTH("account_health", R.string.account_health, DashboardCardSize.HALF),
    TOP_FOLLOWERS("top_followers", R.string.top_followers, DashboardCardSize.TOP_FOLLOWERS),
    ALL_TIME_POST("all_time_post", R.string.all_time_banger, DashboardCardSize.POST),
    BEST_POST("best_post_card", R.string.best_post, DashboardCardSize.POST),
    WORST_POST("worst_post_card", R.string.worst_post, DashboardCardSize.POST),
    FOLLOWERS("followers", R.string.followers, DashboardCardSize.CHART),
    FOLLOWING("following", R.string.following, DashboardCardSize.CHART),
    POSTS("posts", R.string.posts, DashboardCardSize.CHART),
    LIKES("likes", R.string.likes, DashboardCardSize.CHART);

    companion object {
        fun fromId(id: String): DashboardCardType? = entries.firstOrNull { it.id == id }
    }
}

internal data class InsightSpec(
    val label: String,
    val value: String,
    val detail: String,
    val accent: Int,
    val progress: Int? = null,
)

internal data class ChartBinding(
    val layoutRes: Int,
    val valueId: Int,
    val deltaId: Int,
    val chartId: Int,
    val value: String,
    val known: (HistorySample) -> Boolean,
    val selector: (HistorySample) -> Long,
)

internal class MainDashboardBinder(
    private val activity: MainActivity,
) {
    private val heavyTypeface: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.create("sec", Typeface.NORMAL), 700, false)
        } else {
            Typeface.create("sec", Typeface.BOLD)
        }
    }

    private val editModeController get() = activity.editModeController

    fun bindContent() {
        val host = activity.findViewById<FrameLayout>(R.id.main_content_host)
        val skeleton = host.findViewById<View>(R.id.main_launch_skeleton)
        val page = host.findViewById<View>(R.id.main_account_page)
            ?: LayoutInflater.from(activity).inflate(R.layout.main_account_page, host, false)
                .also { host.addView(it, 0) }
        bindPage(page, activity.selectedAccount)
        // Keep the static skeleton above the dashboard until every cached card
        // has bound, then reveal the completed page in one frame.
        skeleton?.let(host::removeView)
    }

    private fun bindPage(page: View, account: String) {
        val stats = TwidgetStore.currentStats(activity, account)
        // Post analytics load from cache instantly; a background refresh below
        // repaints when fresh data arrives.
        activity.analytics = AnalyticsClient.cached(activity, account)
        // Daily samples drive the numbers; the chart list is the same week
        // bucketed down to a readable bar count. Analytics are fixed to the
        // weekly window — the old range chips are gone.
        val history = TwidgetStore.rangedHistory(activity, account, HistoryRange.WEEK)
        val chartHistory = TwidgetStore.chartHistory(activity, account, HistoryRange.WEEK)
        val fullHistory = TwidgetStore.fullHistory(activity, account)
        activity.importedAnalytics = ImportedAnalyticsStore.recent(activity, account)
        bindPrivateAccountNotice(page, stats)
        bindHistoryNotice(page, chartHistory)
        val container = page.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        container.columnCount = DASHBOARD_GRID_COLUMNS
        // Rebuild synchronously behind the launch skeleton. LayoutTransition's
        // default APPEARING animation otherwise exposes a frame where every
        // newly added card is still transparent.
        container.layoutTransition = null
        container.setOnDragListener { source, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> editModeController.editMode && (event.localState as? String) != null
                DragEvent.ACTION_DRAG_LOCATION -> {
                    editModeController.updateDashboardDragAutoScroll(source, event)
                    true
                }
                DragEvent.ACTION_DROP -> {
                    editModeController.finishDashboardDrag(commit = true)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    editModeController.finishDashboardDrag(commit = false)
                    true
                }
                else -> true
            }
        }
        container.removeAllViews()

        TwidgetStore.dashboardCards(activity)
            .mapNotNull(DashboardCardType::fromId)
            .filter { !it.requiresAnalyticsImport() || editModeController.hasAnalyticsImport() }
            .forEach { card ->
                val content = if (card == DashboardCardType.TOP_FOLLOWERS) {
                    createTopFollowersCard(account)
                } else if (card in POST_CARD_TYPES) {
                    activity.postAnalyticsBinder.createGridCard(card, account)
                } else if (card.size == DashboardCardSize.CHART) {
                    createChartCard(card, account, stats, chartHistory, fullHistory)
                } else if (card == DashboardCardType.MILESTONE) {
                    createMilestoneCard(stats, history, account)
                } else {
                    createInsightCard(card, stats, history)
                }
                val wrapper = createDashboardCardWrapper(card, content)
                container.addView(
                    wrapper,
                    dashboardCardLayoutParams(
                        card,
                        content.minimumHeight.takeIf { card == DashboardCardType.TOP_FOLLOWERS && it > 0 },
                    ),
                )
            }

        // Card movement still animates in edit mode, but initial/rebound cards
        // are immediately visible when the skeleton is removed.
        container.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            disableTransitionType(LayoutTransition.CHANGE_APPEARING)
            disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(140)
        }

        activity.syncController.maybeRefreshAnalytics(account)
        activity.syncController.maybeRefreshStreak(account)
    }

    private fun bindHistoryNotice(page: View, chartHistory: List<HistorySample>) {
        val notice = page.findViewById<TextView>(R.id.history_notice) ?: return
        // The daily-capture explanation lives in onboarding now; only the
        // estimate footnote still surfaces on the dashboard.
        if (chartHistory.any { it.estimated }) {
            notice.setText(R.string.estimated_notice)
            notice.visibility = View.VISIBLE
        } else {
            notice.visibility = View.GONE
        }
    }

    private fun bindPrivateAccountNotice(page: View, stats: ProfileStats) {
        page.findViewById<TextView>(R.id.private_account_notice)?.visibility =
            if (stats.isPrivate == true) View.VISIBLE else View.GONE
    }

    // Net change across the whole visible range (last bucket minus first).
    private fun rangeDelta(history: List<HistorySample>, selector: (HistorySample) -> Long): Long {
        if (history.size < 2) return 0
        return selector(history.last()) - selector(history.first())
    }

    private fun createInsightCard(card: DashboardCardType, stats: ProfileStats, history: List<HistorySample>): View {
        val spec = insightSpec(card, stats, history)
        val valueTextSize = if (card.size == DashboardCardSize.FULL) 38f else 32f
        val labelTextSize = 13f
        val detailTextSize = 14f
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
            background = AppCompatResources.getDrawable(activity, R.drawable.metric_card_bg)

            val labelRow = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            labelRow.addView(View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(spec.accent)
                }
            }, LinearLayout.LayoutParams(activity.dp(8), activity.dp(8)))
            labelRow.addView(TextView(activity).apply {
                text = spec.label
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activity.getColor(R.color.oneui_text_secondary))
                textSize = labelTextSize
                typeface = Typeface.create("sec", Typeface.BOLD)
                setPadding(activity.dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(labelRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            // Auto-size needs a bounded height to reach the max size — with
            // wrap_content it locks to the first measured bounds. Fix the row
            // height to the max text size's line and let width do the shrinking.
            val valueHeight = (valueTextSize * 1.3f * resources.displayMetrics.scaledDensity).toInt()
            addView(TextView(activity).apply {
                text = spec.value
                includeFontPadding = false
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setTextColor(activity.getColor(R.color.oneui_text_primary))
                typeface = heavyTypeface
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this, 16, valueTextSize.toInt(), 1, TypedValue.COMPLEX_UNIT_SP,
                )
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                valueHeight,
            ).apply {
                topMargin = activity.dp(4)
            })

            if (spec.progress != null) {
                val progressValue = spec.progress.coerceIn(0, 100)
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.milestone_progress_percent, progressValue)
                    includeFontPadding = false
                    gravity = Gravity.END
                    setTextColor(spec.accent)
                    textSize = 12f
                    typeface = Typeface.create("sec", Typeface.BOLD)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = activity.dp(9)
                })
                addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = progressValue
                    progressTintList = ColorStateList.valueOf(spec.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.oneui_divider))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dp(6),
                ).apply {
                    topMargin = activity.dp(4)
                })
            }

            addView(TextView(activity).apply {
                text = spec.detail
                includeFontPadding = false
                maxLines = if (spec.progress == null) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activity.getColor(R.color.oneui_text_secondary))
                textSize = detailTextSize
                setPadding(0, activity.dp(7), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun createMilestoneCard(stats: ProfileStats, history: List<HistorySample>, account: String): View =
        FrameLayout(activity).apply {
            addView(
                createInsightCard(DashboardCardType.MILESTONE, stats, history).apply {
                    setPadding(paddingLeft, paddingTop, paddingRight, activity.dp(34))
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            if (!editModeController.editMode) {
                addView(
                    MilestoneEditDialog.createEditButton(activity, account) { bindContent() },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END,
                    ).apply {
                        marginEnd = activity.dp(10)
                        bottomMargin = activity.dp(8)
                    },
                )
            }
        }

    private fun createTopFollowersCard(account: String): View {
        return TopFollowersCardBinder(
            activity = activity,
            onStateChanged = { activity.dashboardBinder.bindContent() },
            requestNotificationPermission = { activity.requestTopFollowersNotificationPermission() },
        ).create(account)
    }

    private fun createChartCard(
        card: DashboardCardType,
        account: String,
        stats: ProfileStats,
        chartHistory: List<HistorySample>,
        fullHistory: List<HistorySample>,
    ): View {
        val (layoutRes, valueId, deltaId, chartId, value, known, selector) = when (card) {
            DashboardCardType.FOLLOWERS -> ChartBinding(
                R.layout.metric_card_followers,
                R.id.followers_value,
                R.id.followers_delta,
                R.id.followers_chart,
                if (stats.followersKnown) fullCount(stats.followersCount) else "--",
                { it.followersKnown },
            ) { it.followers }
            DashboardCardType.FOLLOWING -> ChartBinding(
                R.layout.metric_card_following,
                R.id.following_value,
                R.id.following_delta,
                R.id.following_chart,
                if (stats.followingKnown) fullCount(stats.followingsCount) else "--",
                { it.followingKnown },
            ) { it.following }
            DashboardCardType.POSTS -> ChartBinding(
                R.layout.metric_card_posts,
                R.id.posts_value,
                R.id.posts_delta,
                R.id.posts_chart,
                if (stats.postsKnown) fullCount(stats.statusesCount) else "--",
                { it.postsKnown },
            ) { it.posts }
            DashboardCardType.LIKES -> ChartBinding(
                R.layout.metric_card_likes,
                R.id.likes_value,
                R.id.likes_delta,
                R.id.likes_chart,
                if (stats.likesKnown) fullCount(stats.likeCount) else "--",
                { it.likesKnown },
            ) { it.likes }
            else -> error("Compact cards do not have chart layouts.")
        }
        return LayoutInflater.from(activity).inflate(layoutRes, null, false).also { root ->
            bindMetric(
                root,
                valueId,
                deltaId,
                chartId,
                value,
                TwidgetStore.todayDelta(activity, stats.userName, known, selector),
                chartHistory.filter(known),
                fullHistory.filter(known),
                selector,
                allowSparseAverage = card == DashboardCardType.FOLLOWERS &&
                    fullHistory.any { it.imported && it.followersKnown },
            )
            val openHistory = {
                if (!editModeController.editMode) {
                    activity.startActivity(MetricChartActivity.intent(activity, account, card.id))
                }
            }
            root.setOnClickListener { openHistory() }
            root.findViewById<MetricChartView>(chartId)?.onChartTapListener = openHistory
        }
    }

    private fun createDropPlaceholder(card: DashboardCardType): View =
        FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(22).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(activity.dp(2), activity.getColor(R.color.oneui_accent), activity.dp(10).toFloat(), activity.dp(6).toFloat())
            }
            alpha = 0.75f
            contentDescription = activity.getString(card.labelRes)
        }

    private fun createDashboardCardWrapper(card: DashboardCardType, content: View): FrameLayout =
        FrameLayout(activity).apply {
            tag = card.id
            val longPressHandler = View.OnLongClickListener {
                handleDashboardCardLongPress(card, this)
            }
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (card.size == DashboardCardSize.POST) {
                    FrameLayout.LayoutParams.WRAP_CONTENT
                } else {
                    FrameLayout.LayoutParams.MATCH_PARENT
                },
            ))
            alpha = if (editModeController.editMode) 0.96f else 1f
            setOnLongClickListener(longPressHandler)
            attachCardLongPress(content, longPressHandler)
            setOnDragListener { source, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> editModeController.editMode && (event.localState as? String) != null
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        editModeController.updateDashboardDragAutoScroll(source, event)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        val dragged = event.localState as? String ?: editModeController.draggedCardId
                        if (editModeController.editMode && dragged != null && dragged != card.id) {
                            animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start()
                            editModeController.previewMoveDashboardCard(dragged, card.id)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        editModeController.finishDashboardDrag(commit = true)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        editModeController.finishDashboardDrag(commit = false)
                        animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        true
                    }
                    else -> true
                }
            }
            if (editModeController.editMode) {
                addView(removeCardButton(card), FrameLayout.LayoutParams(activity.dp(36), activity.dp(36), Gravity.TOP or Gravity.END).apply {
                    topMargin = activity.dp(6)
                    marginEnd = activity.dp(6)
                })
            }
        }

    private fun handleDashboardCardLongPress(card: DashboardCardType, dragView: View): Boolean {
        if (!editModeController.editMode) {
            editModeController.setEditMode(true)
        } else {
            editModeController.draggedCardId = card.id
            editModeController.dragPreviewOrder = TwidgetStore.dashboardCards(activity)
            editModeController.dragSourceView = dragView
            val dragShadow = View.DragShadowBuilder(dragView)
            moveDropPlaceholder(card, card.id)
            dragView.visibility = View.GONE
            val started = dragView.startDragAndDrop(
                ClipData.newPlainText("dashboard_card", card.id),
                dragShadow,
                card.id,
                0,
            )
            if (!started) {
                editModeController.finishDashboardDrag(commit = false)
            }
        }
        return true
    }

    private fun attachCardLongPress(
        view: View,
        listener: View.OnLongClickListener,
        isContentRoot: Boolean = true,
    ) {
        // Making every descendant long-clickable also makes plain TextViews
        // and ImageViews consume taps before an interactive row can receive
        // them. Keep edit-mode long press on the card root and on controls
        // that already own clicks; decorative descendants remain transparent
        // to their row's touch target.
        if (isContentRoot || view.isClickable) {
            view.setOnLongClickListener(listener)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachCardLongPress(view.getChildAt(index), listener, isContentRoot = false)
            }
        }
    }

    private fun removeCardButton(card: DashboardCardType): ImageButton =
        ImageButton(activity).apply {
            setImageResource(OneUiIconR.drawable.ic_oui_remove)
            imageTintList = ColorStateList.valueOf(activity.getColor(R.color.metric_red))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activity.getColor(R.color.oneui_card_bg))
                setStroke(activity.dp(1), activity.getColor(R.color.oneui_divider))
            }
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
            contentDescription = activity.getString(R.string.delete)
            setOnClickListener { editModeController.removeDashboardCard(card.id) }
        }

    private fun fullCount(value: Long): String =
        NumberFormat.getIntegerInstance(Locale.US).format(value)

    private fun bindMetric(
        root: View,
        valueId: Int,
        deltaId: Int,
        chartId: Int,
        value: String,
        delta: Long,
        history: List<HistorySample>,
        fullHistory: List<HistorySample>,
        selector: (HistorySample) -> Long,
        allowSparseAverage: Boolean,
    ) {
        root.findViewById<TextView>(valueId)?.apply {
            text = value
            // textStyle="bold" renders Samsung's lighter bold cut; force the
            // same true 700 weight the insight cards use.
            typeface = heavyTypeface
        }
        root.findViewById<TextView>(deltaId)?.apply {
            text = if (delta == 0L) "" else TwidgetStore.signedNumber(delta)
            setTextColor(if (delta < 0) activity.getColor(R.color.metric_red) else activity.getColor(R.color.metric_green))
            visibility = if (delta == 0L) View.GONE else View.VISIBLE
        }
        root.findViewById<MetricChartView>(chartId)?.apply {
            setData(history, selector)
            setAverageSeries(AccountAverageSeries.values(fullHistory, history, selector, allowSparseAverage))
        }
    }

    private fun insightSpec(card: DashboardCardType, stats: ProfileStats, history: List<HistorySample>): InsightSpec {
        val followersDelta = rangeDelta(history) { it.followers }
        return when (card) {
            DashboardCardType.FOLLOWER_RATIO -> {
                val diff = stats.followersCount - stats.followingsCount
                InsightSpec(
                    label = activity.getString(R.string.follower_ratio),
                    value = if (stats.followersKnown && stats.followingKnown) {
                        decimal(stats.followersCount.toDouble() / stats.followingsCount.coerceAtLeast(1), "x")
                    } else "--",
                    detail = if (!stats.followersKnown || !stats.followingKnown) {
                        activity.getString(R.string.unknown_profile_status)
                    } else if (diff >= 0) {
                        activity.getString(R.string.more_followers_than_following, TwidgetStore.compactNumber(diff))
                    } else {
                        activity.getString(R.string.fewer_followers_than_following, TwidgetStore.compactNumber(-diff))
                    },
                    accent = activity.getColor(R.color.oneui_accent),
                )
            }
            DashboardCardType.POST_RATE -> InsightSpec(
                label = activity.getString(R.string.post_rate),
                value = history.filter { it.postsKnown }.let { known ->
                    if (known.size >= 2) decimal(dailyAverage(known) { it.posts }.coerceAtLeast(0.0), "") else "--"
                },
                detail = history.filter { it.postsKnown }.let { known ->
                    if (known.size >= 2) {
                        activity.getString(R.string.per_range, TwidgetStore.signedNumber(rangeDelta(known) { it.posts }))
                    } else activity.getString(R.string.unknown_profile_status)
                },
                accent = activity.getColor(R.color.metric_green),
            )
            DashboardCardType.LIKES_PER_POST -> InsightSpec(
                label = activity.getString(R.string.likes_per_post),
                value = if (stats.likesKnown && stats.postsKnown) {
                    decimal(stats.likeCount.toDouble() / stats.statusesCount.coerceAtLeast(1), "")
                } else "--",
                detail = if (stats.likesKnown) {
                    "${TwidgetStore.compactNumber(stats.likeCount)} ${activity.getString(R.string.likes).lowercase(Locale.US)}"
                } else activity.getString(R.string.unknown_profile_status),
                accent = activity.getColor(R.color.oneui_accent),
            )
            DashboardCardType.MILESTONE -> {
                val milestoneSettings = TwidgetStore.milestoneSettings(activity, stats.userName)
                val spec = MilestonePolicy.resolveCardSpec(
                    followersCount = stats.followersCount,
                    followersKnown = stats.followersKnown,
                    settings = milestoneSettings,
                    autoNextMilestone = ::nextMilestone,
                    autoPreviousMilestone = ::previousMilestone,
                    compactNumber = TwidgetStore::compactNumber,
                    goalReachedText = activity.getString(R.string.milestone_goal_reached),
                    unknownFollowersText = activity.getString(R.string.milestone_unknown_followers),
                    toNextMilestone = { remaining, target ->
                        activity.getString(R.string.to_next_milestone, remaining, target)
                    },
                    milestoneLabel = activity.getString(R.string.milestone_progress),
                )
                InsightSpec(
                    label = spec.label,
                    value = spec.value,
                    detail = spec.detail,
                    accent = activity.getColor(R.color.oneui_accent),
                    progress = spec.progress,
                )
            }
            DashboardCardType.DAILY_STREAK -> {
                val streak = ActivityClient.snapshot(activity, stats.userName)
                InsightSpec(
                    label = activity.getString(R.string.daily_streak),
                    value = if (streak.streak > 0) {
                        activity.getString(R.string.daily_streak_days, streak.streak)
                    } else {
                        activity.getString(R.string.daily_streak_none)
                    },
                    detail = when {
                        streak.activeToday -> activity.getString(R.string.daily_streak_active_today)
                        streak.streak > 0 && streak.lastActiveDay != null ->
                            activity.getString(R.string.daily_streak_last_active, streak.lastActiveDay)
                        else -> activity.getString(R.string.daily_streak_keep_going)
                    },
                    accent = if (streak.streak > 0) {
                        activity.getColor(R.color.metric_green)
                    } else {
                        activity.getColor(R.color.oneui_text_secondary)
                    },
                )
            }
            DashboardCardType.GROWTH_PACE -> {
                val daily = dailyAverage(history) { it.followers }
                InsightSpec(
                    label = "Growth",
                    value = TwidgetStore.signedNumber(followersDelta),
                    detail = activity.getString(R.string.per_day, signedDecimal(daily)),
                    accent = if (followersDelta < 0) activity.getColor(R.color.metric_red) else activity.getColor(R.color.metric_green),
                )
            }
            DashboardCardType.BEST_DAY -> {
                val best = bestRecentDay(history)
                InsightSpec(
                    label = "Best day",
                    value = if (best == null) "--" else TwidgetStore.signedNumber(best.second),
                    detail = best?.first ?: activity.getString(R.string.no_recent_gain),
                    accent = activity.getColor(R.color.metric_green),
                )
            }
            DashboardCardType.MOMENTUM -> {
                val momentum = momentum(history)
                InsightSpec(
                    label = activity.getString(R.string.momentum),
                    value = momentum.first,
                    detail = activity.getString(R.string.per_range, TwidgetStore.signedNumber(followersDelta)),
                    accent = momentum.second,
                )
            }
            DashboardCardType.AUDIENCE_BALANCE -> {
                val ratio = stats.followersCount.toDouble() / stats.followingsCount.coerceAtLeast(1)
                InsightSpec(
                    label = "Balance",
                    value = if (ratio >= 1.0) {
                        String.format(Locale.US, "%.1f:1", ratio)
                    } else {
                        String.format(Locale.US, "1:%.1f", 1.0 / ratio.coerceAtLeast(0.01))
                    },
                    detail = "${TwidgetStore.compactNumber(stats.followersCount)} / ${TwidgetStore.compactNumber(stats.followingsCount)}",
                    accent = activity.getColor(R.color.oneui_accent),
                )
            }
            DashboardCardType.ACCOUNT_HEALTH -> {
                // Never claim Public unless the API explicitly said so.
                InsightSpec(
                    label = "Health",
                    value = when {
                        stats.isVerified == true -> activity.getString(R.string.verified)
                        stats.isPrivate == true -> activity.getString(R.string.private_profile)
                        stats.isPrivate == false -> activity.getString(R.string.public_profile)
                        else -> "--"
                    },
                    detail = when {
                        stats.isVerified == true && stats.isPrivate == true -> activity.getString(R.string.verified_private)
                        stats.isVerified == true && stats.isPrivate == false -> activity.getString(R.string.verified_public)
                        stats.isVerified == true -> activity.getString(R.string.verified)
                        stats.isPrivate == true -> activity.getString(R.string.private_unverified)
                        stats.isPrivate == false -> activity.getString(R.string.public_unverified)
                        else -> activity.getString(R.string.unknown_profile_status)
                    },
                    accent = when {
                        stats.isPrivate == true -> activity.getColor(R.color.metric_red)
                        stats.isVerified == true || stats.isPrivate == false -> activity.getColor(R.color.metric_green)
                        else -> activity.getColor(R.color.oneui_text_secondary)
                    },
                )
            }
            DashboardCardType.ENGAGEMENT_RATE -> blendedAnalyticsSpec(
                activity.getString(R.string.engagement_rate),
                activity.getColor(R.color.oneui_accent),
                { blend -> blend.engagementRate?.let(::percent) },
                { blend -> blend.usesImportedRate },
            )
            DashboardCardType.AVG_VIEWS -> blendedAnalyticsSpec(
                activity.getString(R.string.avg_views),
                activity.getColor(R.color.metric_green),
                { blend -> blend.avgViews?.roundToLong()?.let(TwidgetStore::compactNumber) },
                { blend -> blend.usesImportedViews },
            )
            DashboardCardType.TOTAL_VIEWS -> analyticsSpec(
                activity.getString(R.string.total_views),
                activity.getColor(R.color.oneui_accent),
                { TwidgetStore.compactNumber(it.totalViews) },
                { analyticsCoverage(it) },
            )
            DashboardCardType.AVG_ENGAGEMENTS -> blendedAnalyticsSpec(
                activity.getString(R.string.avg_engagements),
                activity.getColor(R.color.metric_green),
                { blend -> blend.avgEngagements?.roundToLong()?.let(TwidgetStore::compactNumber) },
                { blend -> blend.usesImportedEngagements },
            )
            DashboardCardType.MEDIAN_ENGAGEMENTS -> analyticsSpec(
                activity.getString(R.string.median_engagements),
                activity.getColor(R.color.oneui_accent),
                { TwidgetStore.compactNumber(it.medianEngagements.roundToLong()) },
                { analyticsCoverage(it) },
            )
            DashboardCardType.X_IMPRESSIONS -> importedAnalyticsSpec(
                activity.getString(R.string.x_impressions),
                activity.getColor(R.color.oneui_accent),
            ) { it.impressions }
            DashboardCardType.X_ENGAGEMENTS -> importedAnalyticsSpec(
                activity.getString(R.string.x_engagements),
                activity.getColor(R.color.metric_green),
            ) { it.engagements }
            DashboardCardType.X_PROFILE_VISITS -> importedAnalyticsSpec(
                activity.getString(R.string.x_profile_visits),
                activity.getColor(R.color.oneui_accent),
            ) { it.profileVisits }
            DashboardCardType.X_LIKES_RECEIVED -> importedAnalyticsSpec(
                activity.getString(R.string.x_likes_received),
                activity.getColor(R.color.metric_green),
            ) { it.likes }
            else -> error("Chart cards do not have insight specs.")
        }
    }

    private fun importedAnalyticsSpec(
        label: String,
        accent: Int,
        selector: (XAnalyticsMovement) -> Long?,
    ): InsightSpec {
        val values = activity.importedAnalytics.mapNotNull(selector)
        return InsightSpec(
            label = label,
            value = values.takeIf { it.isNotEmpty() }?.sum()?.let(TwidgetStore::compactNumber) ?: "--",
            detail = if (values.isEmpty()) {
                activity.getString(R.string.import_x_analytics_hint)
            } else {
                activity.getString(R.string.x_analytics_days, values.size)
            },
            accent = accent,
        )
    }

    private fun blendedAnalyticsSpec(
        label: String,
        accent: Int,
        value: (BlendedAnalytics) -> String?,
        usesImported: (BlendedAnalytics) -> Boolean,
    ): InsightSpec {
        val blend = AnalyticsBlendPolicy.blend(activity.analytics, activity.importedAnalytics)
        return InsightSpec(
            label = label,
            value = value(blend) ?: "--",
            detail = when {
                usesImported(blend) -> activity.getString(
                    R.string.analytics_blended_coverage,
                    blend.livePosts,
                    blend.importedDays,
                )
                activity.analytics != null -> analyticsCoverage(requireNotNull(activity.analytics))
                else -> activity.getString(R.string.analytics_syncing)
            },
            accent = accent,
        )
    }

    private fun analyticsCoverage(data: PostAnalytics): String =
        if (data.isSampled) {
            activity.getString(R.string.posts_from_capped_status_sample, data.postsAnalyzed, data.statusesInspected)
        } else {
            activity.getString(R.string.across_posts, data.postsAnalyzed)
        }

    // Analytics cards fall back to a placeholder until the timeline fetch lands.
    private fun analyticsSpec(
        label: String,
        accent: Int,
        value: (PostAnalytics) -> String,
        detail: (PostAnalytics) -> String,
    ): InsightSpec {
        val data = activity.analytics
        return InsightSpec(
            label = label,
            value = data?.let(value) ?: "--",
            detail = data?.let(detail) ?: activity.getString(R.string.analytics_syncing),
            accent = accent,
        )
    }

    private fun percent(fraction: Double): String =
        String.format(Locale.US, "%.2f%%", fraction * 100)

    // Average per real elapsed day — samples can have gaps, so count days
    // between the first and last sample rather than counting samples.
    private fun dailyAverage(history: List<HistorySample>, selector: (HistorySample) -> Long): Double {
        if (history.size < 2) return 0.0
        val days = ((history.last().timestamp - history.first().timestamp) / DAY_MILLIS).coerceAtLeast(1)
        return rangeDelta(history, selector).toDouble() / days
    }

    private fun bestRecentDay(history: List<HistorySample>): Pair<String, Long>? =
        history.zipWithNext()
            .map { (previous, current) -> current.dayLabel to (current.followers - previous.followers) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }

    private fun momentum(history: List<HistorySample>): Pair<String, Int> {
        if (history.size < 4) return activity.getString(R.string.flat) to activity.getColor(R.color.oneui_text_secondary)
        val middle = history.lastIndex / 2
        val firstHalf = history[middle].followers - history.first().followers
        val secondHalf = history.last().followers - history[middle].followers
        val threshold = maxOf(2L, (abs(firstHalf) * 0.25).roundToLong())
        return when {
            secondHalf > firstHalf + threshold -> activity.getString(R.string.accelerating) to activity.getColor(R.color.metric_green)
            secondHalf < firstHalf - threshold -> activity.getString(R.string.cooling) to activity.getColor(R.color.metric_red)
            firstHalf == 0L && secondHalf == 0L -> activity.getString(R.string.flat) to activity.getColor(R.color.oneui_text_secondary)
            else -> "Steady" to activity.getColor(R.color.oneui_accent)
        }
    }

    private fun nextMilestone(value: Long): Long {
        var step = 100L
        while (value >= step * 10) step *= 10
        return ((value / step) + 1) * step
    }

    private fun previousMilestone(milestone: Long): Long {
        var step = 100L
        while (milestone > step * 10) step *= 10
        return milestone - step
    }

    private fun signedDecimal(value: Double): String =
        if (abs(value) >= 10.0) {
            TwidgetStore.signedNumber(value.roundToLong())
        } else {
            val sign = if (value > 0) "+" else ""
            String.format(Locale.US, "%s%.1f", sign, value)
        }

    fun moveDropPlaceholder(card: DashboardCardType, targetId: String) {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        val placeholder = editModeController.dragPlaceholderView ?: FrameLayout(activity).apply {
            tag = DRAG_PLACEHOLDER_TAG
            addView(createDropPlaceholder(card), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            setOnDragListener { source, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> editModeController.editMode && (event.localState as? String) != null
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        editModeController.updateDashboardDragAutoScroll(source, event)
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        editModeController.finishDashboardDrag(commit = true)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        editModeController.finishDashboardDrag(commit = false)
                        true
                    }
                    else -> true
                }
            }
        }.also { editModeController.dragPlaceholderView = it }

        val existingParent = placeholder.parent as? ViewGroup
        var targetIndex = container.childIndexWithTag(targetId)
        if (targetIndex == -1) targetIndex = container.childCount
        if (existingParent === container) {
            val oldIndex = container.indexOfChild(placeholder)
            if (oldIndex != -1 && oldIndex < targetIndex) targetIndex--
            container.removeView(placeholder)
        } else {
            existingParent?.removeView(placeholder)
        }
        container.addView(
            placeholder,
            targetIndex.coerceIn(0, container.childCount),
            dashboardCardLayoutParams(card, editModeController.dragSourceView?.height),
        )
    }

    private fun ViewGroup.childIndexWithTag(tagValue: String): Int {
        for (index in 0 until childCount) {
            if (getChildAt(index).tag == tagValue) return index
        }
        return -1
    }

    private fun dashboardCardLayoutParams(card: DashboardCardType, dragHeight: Int? = null): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = when {
                dragHeight != null && dragHeight > 0 -> dragHeight
                card.size == DashboardCardSize.POST -> ViewGroup.LayoutParams.WRAP_CONTENT
                else -> activity.dp(card.size.heightDp)
            }
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, card.size.span, 1f)
            setMargins(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(5))
        }

    private fun decimal(value: Double, suffix: String): String =
        String.format(Locale.US, "%.1f%s", value, suffix)

    private companion object {
        private const val DASHBOARD_GRID_COLUMNS = 2
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val DRAG_PLACEHOLDER_TAG = "dashboard_drop_placeholder"
        private val POST_CARD_TYPES = setOf(
            DashboardCardType.ALL_TIME_POST,
            DashboardCardType.BEST_POST,
            DashboardCardType.WORST_POST,
        )
    }
}

internal fun DashboardCardType.requiresAnalyticsImport(): Boolean = when (this) {
    DashboardCardType.X_IMPRESSIONS,
    DashboardCardType.X_ENGAGEMENTS,
    DashboardCardType.X_PROFILE_VISITS,
    DashboardCardType.X_LIKES_RECEIVED,
    -> true
    else -> false
}
