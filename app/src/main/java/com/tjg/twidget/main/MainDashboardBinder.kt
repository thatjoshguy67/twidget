package com.tjg.twidget.main

import android.animation.LayoutTransition
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.tjg.twidget.R
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
import com.tjg.twidget.schedule.ScheduleAccentChrome
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.oneUiAccent
import com.tjg.twidget.ui.oneUiCardBackground
import com.tjg.twidget.ui.oneUiDivider
import com.tjg.twidget.ui.oneUiTextPrimary
import com.tjg.twidget.ui.oneUiTextSecondary
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
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
    private var lastDragScreenX = Float.NaN
    private var lastDragScreenY = Float.NaN
    private var pendingPreviewCard: DashboardCardType? = null
    private var pendingPreviewOrder: List<String>? = null
    private var pendingPreviewInsertAt = -1
    private var savedDashboardLayoutTransition: LayoutTransition? = null
    private var savedBottomSlotHeight: Int? = null
    private var pendingDragLocationRefresh = false
    private var lastRefreshScreenX = Float.NaN
    private var lastRefreshScreenY = Float.NaN
    private var dragCardViewCache: Map<String, View>? = null
    private var dragDashboardContainer: GridLayout? = null
    private var dragDashboardScroll: NestedScrollView? = null
    private val applyPendingPreviewOrder = Runnable {
        if (editModeController.draggedCardId == null) return@Runnable
        val card = pendingPreviewCard ?: return@Runnable
        val order = pendingPreviewOrder ?: return@Runnable
        val insertAt = pendingPreviewInsertAt
        pendingPreviewCard = null
        pendingPreviewOrder = null
        pendingPreviewInsertAt = -1
        applyPreviewOrder(card, order, insertAt)
    }
    private val refreshDragLocationRunnable = Runnable {
        pendingDragLocationRefresh = false
        refreshDashboardDragLocation()
    }

    fun suspendDashboardLayoutTransition() {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        if (savedDashboardLayoutTransition == null) {
            savedDashboardLayoutTransition = container.layoutTransition
        }
        container.layoutTransition = null
    }

    fun restoreDashboardLayoutTransition() {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        savedDashboardLayoutTransition?.let { container.layoutTransition = it }
        savedDashboardLayoutTransition = null
    }

    fun showDashboardBottomDropZone(height: Int) {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        ensureEditModeBottomSlot(container)
        val slot = container.childWithTag(EDIT_MODE_BOTTOM_SLOT_TAG) ?: return
        if (savedBottomSlotHeight == null) {
            savedBottomSlotHeight = slot.layoutParams.height
        }
        val expandedHeight = (savedBottomSlotHeight ?: 0).coerceAtLeast(height.coerceAtLeast(0))
        if (slot.layoutParams.height != expandedHeight) {
            slot.layoutParams = slot.layoutParams.apply { this.height = expandedHeight }
            slot.requestLayout()
        }
    }

    fun hideDashboardBottomDropZone() {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        val slot = container.childWithTag(EDIT_MODE_BOTTOM_SLOT_TAG) ?: return
        val restoredHeight = savedBottomSlotHeight ?: return
        if (slot.layoutParams.height != restoredHeight) {
            slot.layoutParams = slot.layoutParams.apply { height = restoredHeight }
            slot.requestLayout()
        }
        savedBottomSlotHeight = null
    }

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
        ScheduleAccentChrome.apply(activity)
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
        container.clipChildren = true
        container.clipToPadding = true
        // Rebuild synchronously behind the launch skeleton. LayoutTransition's
        // default APPEARING animation otherwise exposes a frame where every
        // newly added card is still transparent.
        container.layoutTransition = null
        container.setOnDragListener(createDashboardDragListener(handleFinish = true))
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
                    createChartCard(card, stats, chartHistory, history, fullHistory)
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
            if (editModeController.editMode) {
                enableTransitionType(LayoutTransition.CHANGE_APPEARING)
                enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
            } else {
                disableTransitionType(LayoutTransition.CHANGE_APPEARING)
                disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
            }
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(DASHBOARD_MOVE_DURATION_MS)
            val interpolator = DecelerateInterpolator()
            setInterpolator(LayoutTransition.CHANGING, interpolator)
            setInterpolator(LayoutTransition.CHANGE_APPEARING, interpolator)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, interpolator)
        }

        if (editModeController.editMode) {
            applyDashboardEditModeState(container)
            ensureEditModeBottomSlot(container)
        }

        activity.syncController.maybeRefreshAnalytics(account)
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
                setTextColor(activity.oneUiTextSecondary())
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
                setTextColor(activity.oneUiTextPrimary())
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
                addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = spec.progress.coerceIn(0, 100)
                    progressTintList = ColorStateList.valueOf(spec.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(activity.oneUiDivider())
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dp(6),
                ).apply {
                    topMargin = activity.dp(9)
                })
            }

            addView(TextView(activity).apply {
                text = spec.detail
                includeFontPadding = false
                maxLines = if (spec.progress == null) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activity.oneUiTextSecondary())
                textSize = detailTextSize
                setPadding(0, activity.dp(7), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun createTopFollowersCard(account: String): View {
        return TopFollowersCardBinder(
            activity = activity,
            isEditMode = { editModeController.editMode },
            onStateChanged = { activity.dashboardBinder.bindContent() },
            requestNotificationPermission = { activity.requestTopFollowersNotificationPermission() },
        ).create(account)
    }

    private fun createChartCard(
        card: DashboardCardType,
        stats: ProfileStats,
        chartHistory: List<HistorySample>,
        history: List<HistorySample>,
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
        return LayoutInflater.from(activity).inflate(layoutRes, null, false).also {
            bindMetric(
                it,
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
        }
    }

    private fun createDropPlaceholder(card: DashboardCardType): View =
        FrameLayout(activity).apply {
            alpha = 0.7f
            background = GradientDrawable().apply {
                cornerRadius = activity.dp(22).toFloat()
                setColor(activity.oneUiCardBackground())
                setStroke(activity.dp(2), activity.oneUiAccent(), activity.dp(10).toFloat(), activity.dp(6).toFloat())
            }
            contentDescription = activity.getString(card.labelRes)
        }

    private fun createDashboardCardWrapper(card: DashboardCardType, content: View): FrameLayout =
        FrameLayout(activity).apply {
            tag = card.id
            clipChildren = true
            clipToPadding = true
            val dragGesture = CardDragGesture().also { setTag(R.id.dashboard_drag_gesture, it) }
            val touchHandler = View.OnTouchListener { _, event ->
                handleDashboardCardTouch(card, this, dragGesture, event)
            }
            val blockDescendantGestures = editModeController.editMode
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            if (blockDescendantGestures) {
                ensureEditModeTouchShield(this, touchHandler)
            }
            alpha = if (editModeController.editMode) 0.96f else 1f
            setOnTouchListener(touchHandler)
            attachCardGesture(
                content,
                touchHandler,
                blockDescendantGestures = blockDescendantGestures,
            )
            setOnDragListener(createDashboardDragListener(handleFinish = false))
            if (editModeController.editMode) {
                addView(removeCardButton(card), FrameLayout.LayoutParams(activity.dp(36), activity.dp(36), Gravity.TOP or Gravity.END).apply {
                    topMargin = activity.dp(6)
                    marginEnd = activity.dp(6)
                })
            }
        }

    private fun handleDashboardCardLongPress(
        card: DashboardCardType,
        dragView: View,
        gesture: CardDragGesture,
    ) {
        if (!editModeController.editMode) {
            editModeController.enterEditModeForDrag()
        }
        gesture.armed = true
        gesture.suppressClick = true
        dragView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun scheduleDashboardLongPress(
        card: DashboardCardType,
        dragView: View,
        gesture: CardDragGesture,
    ) {
        cancelDashboardLongPress(dragView, gesture)
        val runnable = Runnable {
            gesture.longPressPosted = false
            gesture.longPressRunnable = null
            handleDashboardCardLongPress(card, dragView, gesture)
        }
        gesture.longPressRunnable = runnable
        gesture.longPressPosted = true
        dragView.postDelayed(runnable, EDIT_DRAG_LONG_PRESS_MS)
    }

    private fun cancelDashboardLongPress(dragView: View, gesture: CardDragGesture) {
        gesture.longPressRunnable?.let(dragView::removeCallbacks)
        gesture.longPressRunnable = null
        gesture.longPressPosted = false
    }

    private fun handleDashboardCardTouch(
        card: DashboardCardType,
        dragView: View,
        gesture: CardDragGesture,
        event: MotionEvent,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture.downRawX = event.rawX
                gesture.downRawY = event.rawY
                val location = IntArray(2)
                dragView.getLocationOnScreen(location)
                gesture.downLocalX = event.rawX - location[0]
                gesture.downLocalY = event.rawY - location[1]
                gesture.armed = false
                gesture.started = false
                gesture.suppressClick = false
                scheduleDashboardLongPress(card, dragView, gesture)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gesture.armed && gesture.longPressPosted) {
                    val distance = hypot(event.rawX - gesture.downRawX, event.rawY - gesture.downRawY)
                    if (distance >= activity.dp(DRAG_START_SLOP_DP)) {
                        cancelDashboardLongPress(dragView, gesture)
                    }
                }
                if (gesture.armed && !gesture.started) {
                    val distance = hypot(event.rawX - gesture.downRawX, event.rawY - gesture.downRawY)
                    if (distance >= activity.dp(DRAG_START_SLOP_DP)) {
                        gesture.started = startDashboardDrag(card, dragView)
                        gesture.armed = false
                        return gesture.started
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                cancelDashboardLongPress(dragView, gesture)
                if (gesture.suppressClick) {
                    gesture.suppressClick = false
                    gesture.armed = false
                    return true
                }
                gesture.armed = false
            }
        }
        return gesture.suppressClick
    }

    private fun startDashboardDrag(card: DashboardCardType, dragView: View): Boolean {
        if (editModeController.draggedCardId != null) return false
        editModeController.beginDashboardDrag(card.id, dragView)
        val gesture = dragView.getTag(R.id.dashboard_drag_gesture) as? CardDragGesture
        val touchX = gesture?.downLocalX ?: (dragView.width / 2f)
        val touchY = gesture?.downLocalY ?: (dragView.height / 2f)
        val label = activity.getString(card.labelRes)
        val shadowBuilder = if (usesLightDragShadow(card)) {
            AccentCardDragShadowBuilder(
                activity = activity,
                label = label,
                source = dragView,
                touchPointX = touchX,
                touchPointY = touchY,
            )
        } else {
            CachedCardDragShadowBuilder(
                activity = activity,
                label = label,
                source = dragView,
                touchPointX = touchX,
                touchPointY = touchY,
            )
        }
        val started = dragView.startDragAndDrop(
            ClipData.newPlainText("dashboard_card", card.id),
            shadowBuilder,
            card.id,
            0,
        )
        if (started) {
            detachDragSourceIfNeeded(dragView)
        } else {
            editModeController.finishDashboardDrag(commit = false)
        }
        return started
    }

    fun detachDragSourceIfNeeded(source: View) {
        if (editModeController.dragSourceDetached) return
        detachDragSource(source)
    }

    private fun detachDragSource(source: View) {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        if (source.parent !== container) return
        editModeController.markDragSourceDetached(source.layoutParams)
        container.removeView(source)
    }

    fun restoreDragSourceOnCancel() {
        if (!editModeController.dragSourceDetached) return
        val source = editModeController.dragSourceView ?: return
        val layoutParams = editModeController.dragSourceLayoutParams ?: return
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        if (source.parent === container) return
        val placeholder = editModeController.dragPlaceholderView
        val insertAt = placeholder?.let { container.indexOfChild(it) }?.takeIf { it >= 0 }
            ?: editModeController.dragInsertAt.coerceAtLeast(0)
        source.alpha = 0.96f
        container.addView(
            source,
            insertAt.coerceIn(0, indexBeforeEditModeBottomSlot(container)),
            layoutParams,
        )
    }

    private fun attachCardGesture(
        view: View,
        touchListener: View.OnTouchListener,
        isContentRoot: Boolean = true,
        blockDescendantGestures: Boolean = false,
    ) {
        // Making every descendant long-clickable also makes plain TextViews
        // and ImageViews consume taps before an interactive row can receive
        // them. Keep edit-mode long press on the card root and on controls
        // that already own clicks; decorative descendants remain transparent
        // to their row's touch target.
        if (isContentRoot || (!blockDescendantGestures && view.isClickable)) {
            view.setOnTouchListener(touchListener)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachCardGesture(
                    view.getChildAt(index),
                    touchListener,
                    isContentRoot = false,
                    blockDescendantGestures = blockDescendantGestures,
                )
            }
        }
    }

    private fun ensureEditModeTouchShield(wrapper: FrameLayout, touchHandler: View.OnTouchListener? = null) {
        val existingShield = (0 until wrapper.childCount)
            .map(wrapper::getChildAt)
            .firstOrNull { it.tag == EDIT_MODE_TOUCH_SHIELD_TAG }

        val handler = touchHandler ?: run {
            val card = (wrapper.tag as? String)?.let(DashboardCardType::fromId) ?: return
            val gesture = wrapper.getTag(R.id.dashboard_drag_gesture) as? CardDragGesture ?: CardDragGesture().also {
                wrapper.setTag(R.id.dashboard_drag_gesture, it)
            }
            View.OnTouchListener { _, event ->
                handleDashboardCardTouch(card, wrapper, gesture, event)
            }
        }

        val shield = existingShield ?: createEditModeTouchShield(wrapper, handler).also { shieldView ->
            val insertIndex = (0 until wrapper.childCount).count { index ->
                val child = wrapper.getChildAt(index)
                child.tag != REMOVE_BUTTON_TAG && child.tag != EDIT_MODE_TOUCH_SHIELD_TAG
            }
            wrapper.addView(
                shieldView,
                insertIndex,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        positionEditModeTouchShield(wrapper, shield)
    }

    private fun positionEditModeTouchShield(wrapper: FrameLayout, shield: View) {
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        wrapper.removeView(shield)
        val removeIndex = (0 until wrapper.childCount).indexOfFirst { wrapper.getChildAt(it).tag == REMOVE_BUTTON_TAG }
        if (removeIndex >= 0) {
            wrapper.addView(shield, removeIndex, layoutParams)
        } else {
            wrapper.addView(shield, layoutParams)
        }
    }

    private fun createEditModeTouchShield(
        wrapper: FrameLayout,
        touchHandler: View.OnTouchListener,
    ): View =
        View(activity).apply {
            tag = EDIT_MODE_TOUCH_SHIELD_TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setOnTouchListener { _, event ->
                touchHandler.onTouch(wrapper, event)
                true
            }
        }

    private fun dashboardCardContentView(wrapper: FrameLayout): View? =
        (0 until wrapper.childCount)
            .map(wrapper::getChildAt)
            .firstOrNull { child ->
                child.tag != REMOVE_BUTTON_TAG && child.tag != EDIT_MODE_TOUCH_SHIELD_TAG
            }

    private fun applyDashboardEditModeState(container: GridLayout) {
        for (index in 0 until container.childCount) {
            val wrapper = container.getChildAt(index) as? FrameLayout ?: continue
            val card = (wrapper.tag as? String)?.let(DashboardCardType::fromId) ?: continue
            ensureEditModeTouchShield(wrapper)
            applyCardEditModeState(card, dashboardCardContentView(wrapper), editMode = true)
        }
    }

    private fun applyCardEditModeState(card: DashboardCardType, content: View?, editMode: Boolean) {
        if (content == null) return
        when (card) {
            DashboardCardType.TOP_FOLLOWERS ->
                TopFollowersCardBinder.applyEditModeState(activity, content, editMode)
            in POST_CARD_TYPES ->
                PostCardBinder.applyEditModeState(activity, content, editMode)
            else -> Unit
        }
    }

    private fun removeCardButton(card: DashboardCardType): ImageButton =
        ImageButton(activity).apply {
            tag = REMOVE_BUTTON_TAG
            setImageResource(OneUiIconR.drawable.ic_oui_remove)
            imageTintList = ColorStateList.valueOf(activity.getColor(R.color.metric_red))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activity.oneUiCardBackground())
                setStroke(activity.dp(1), activity.oneUiDivider())
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
                    accent = activity.oneUiAccent(),
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
                accent = activity.oneUiAccent(),
            )
            DashboardCardType.MILESTONE -> {
                val milestone = nextMilestone(stats.followersCount)
                val previous = previousMilestone(milestone)
                val remaining = (milestone - stats.followersCount).coerceAtLeast(0)
                val progress = if (milestone == previous) 100 else (((stats.followersCount - previous).coerceAtLeast(0) * 100) / (milestone - previous)).toInt()
                InsightSpec(
                    label = "Milestone",
                    value = TwidgetStore.compactNumber(milestone),
                    detail = activity.getString(R.string.to_next_milestone, TwidgetStore.compactNumber(remaining), TwidgetStore.compactNumber(milestone)),
                    accent = activity.oneUiAccent(),
                    progress = progress,
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
                    accent = activity.oneUiAccent(),
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
                        else -> activity.oneUiTextSecondary()
                    },
                )
            }
            DashboardCardType.ENGAGEMENT_RATE -> blendedAnalyticsSpec(
                activity.getString(R.string.engagement_rate),
                activity.oneUiAccent(),
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
                activity.oneUiAccent(),
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
                activity.oneUiAccent(),
                { TwidgetStore.compactNumber(it.medianEngagements.roundToLong()) },
                { analyticsCoverage(it) },
            )
            DashboardCardType.X_IMPRESSIONS -> importedAnalyticsSpec(
                activity.getString(R.string.x_impressions),
                activity.oneUiAccent(),
            ) { it.impressions }
            DashboardCardType.X_ENGAGEMENTS -> importedAnalyticsSpec(
                activity.getString(R.string.x_engagements),
                activity.getColor(R.color.metric_green),
            ) { it.engagements }
            DashboardCardType.X_PROFILE_VISITS -> importedAnalyticsSpec(
                activity.getString(R.string.x_profile_visits),
                activity.oneUiAccent(),
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
        if (history.size < 4) return activity.getString(R.string.flat) to activity.oneUiTextSecondary()
        val middle = history.lastIndex / 2
        val firstHalf = history[middle].followers - history.first().followers
        val secondHalf = history.last().followers - history[middle].followers
        val threshold = maxOf(2L, (abs(firstHalf) * 0.25).roundToLong())
        return when {
            secondHalf > firstHalf + threshold -> activity.getString(R.string.accelerating) to activity.getColor(R.color.metric_green)
            secondHalf < firstHalf - threshold -> activity.getString(R.string.cooling) to activity.getColor(R.color.metric_red)
            firstHalf == 0L && secondHalf == 0L -> activity.getString(R.string.flat) to activity.oneUiTextSecondary()
            else -> "Steady" to activity.oneUiAccent()
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

    fun applyEditModeVisuals(animate: Boolean = true) {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        container.layoutTransition?.apply {
            enableTransitionType(LayoutTransition.CHANGE_APPEARING)
            enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
        }
        ensureEditModeBottomSlot(container)
        for (index in 0 until container.childCount) {
            val wrapper = container.getChildAt(index) as? FrameLayout ?: continue
            val card = (wrapper.tag as? String)?.let(DashboardCardType::fromId) ?: continue
            if (animate) {
                wrapper.animate()
                    .alpha(0.96f)
                    .scaleX(0.985f)
                    .scaleY(0.985f)
                    .setDuration(DASHBOARD_MOVE_DURATION_MS)
                    .withEndAction {
                        wrapper.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(DASHBOARD_MOVE_DURATION_MS)
                            .start()
                    }
                    .start()
            } else {
                wrapper.alpha = 0.96f
            }
            if ((0 until wrapper.childCount).none { wrapper.getChildAt(it).tag == REMOVE_BUTTON_TAG }) {
                wrapper.addView(
                    removeCardButton(card),
                    FrameLayout.LayoutParams(activity.dp(36), activity.dp(36), Gravity.TOP or Gravity.END).apply {
                        topMargin = activity.dp(6)
                        marginEnd = activity.dp(6)
                    },
                )
            }
            ensureEditModeTouchShield(wrapper)
            if (card == DashboardCardType.TOP_FOLLOWERS || card in POST_CARD_TYPES) {
                applyCardEditModeState(card, dashboardCardContentView(wrapper), editMode = true)
            }
        }
    }

    fun beginDashboardDragSession() {
        dragDashboardContainer = activity.findViewById(R.id.dashboard_content)
        dragDashboardScroll = activity.findViewById(R.id.dashboard_scroll)
        val container = dragDashboardContainer ?: return
        for (index in 0 until container.childCount) {
            container.getChildAt(index).animate().cancel()
        }
        dragCardViewCache = buildMap {
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                val id = child.tag as? String ?: continue
                if (DashboardCardType.fromId(id) != null) {
                    put(id, child)
                }
            }
        }
    }

    fun endDashboardDragSession() {
        dragCardViewCache = null
        dragDashboardContainer = null
        dragDashboardScroll = null
    }

    fun handleDashboardDragLocation(source: View, event: DragEvent) {
        editModeController.updateDashboardDragAutoScroll(source, event)
        val sourceLocation = IntArray(2).also(source::getLocationOnScreen)
        val nextX = sourceLocation[0] + event.x
        val nextY = sourceLocation[1] + event.y
        val movedEnough = !lastRefreshScreenX.isFinite() ||
            hypot(nextX - lastRefreshScreenX, nextY - lastRefreshScreenY) >= activity.dp(DRAG_LOCATION_MIN_STEP_DP)
        lastDragScreenX = nextX
        lastDragScreenY = nextY
        if (movedEnough) {
            scheduleRefreshDashboardDragLocation()
        }
    }

    fun scheduleRefreshDashboardDragLocation() {
        if (pendingDragLocationRefresh) return
        val container = dashboardContainer() ?: return
        pendingDragLocationRefresh = true
        container.postOnAnimation(refreshDragLocationRunnable)
    }

    fun refreshDashboardDragLocation() {
        if (!lastDragScreenX.isFinite() || !lastDragScreenY.isFinite()) return
        lastRefreshScreenX = lastDragScreenX
        lastRefreshScreenY = lastDragScreenY
        val container = dashboardContainer() ?: return
        val draggedId = editModeController.draggedCardId ?: return
        val order = editModeController.dragPreviewOrder ?: return
        val containerLocation = IntArray(2).also(container::getLocationOnScreen)
        val pointerX = lastDragScreenX - containerLocation[0]
        val pointerY = lastDragScreenY - containerLocation[1]
        val bottomSlot = container.childWithTag(EDIT_MODE_BOTTOM_SLOT_TAG)
        val cards = order.asSequence()
            .filterNot { it == draggedId }
            .mapNotNull { id ->
                val view = cardViewById(id) ?: return@mapNotNull null
                val card = DashboardCardType.fromId(id) ?: return@mapNotNull null
                DashboardGridSlots.CardBounds(
                    id = id,
                    left = view.left.toFloat(),
                    top = view.top.toFloat(),
                    right = view.right.toFloat(),
                    bottom = view.bottom.toFloat(),
                    span = card.size.span,
                )
            }
            .toList()
        if (
            bottomSlot != null &&
            pointerX >= bottomSlot.left &&
            pointerX <= bottomSlot.right &&
            pointerY >= bottomSlot.top &&
            pointerY <= bottomSlot.bottom
        ) {
            if (editModeController.dragInsertAt != cards.size) {
                editModeController.previewMoveDashboardCard(draggedId, cards.size)
            }
            return
        }
        val insertAt = DashboardGridSlots.resolveInsertIndex(
            cards = cards,
            pointerX = pointerX,
            pointerY = pointerY,
            currentInsertAt = editModeController.dragInsertAt,
            hysteresisPx = activity.dp(DRAG_SLOT_HYSTERESIS_DP).toFloat(),
            bottomGutterPx = bottomSlot?.height?.toFloat()
                ?: activity.dp(DRAG_BOTTOM_GUTTER_DP).toFloat(),
        )
        if (insertAt != editModeController.dragInsertAt) {
            editModeController.previewMoveDashboardCard(draggedId, insertAt)
        }
    }

    fun schedulePreviewOrder(card: DashboardCardType, order: List<String>, insertAt: Int) {
        val container = dashboardContainer() ?: return
        pendingPreviewCard = card
        pendingPreviewOrder = order
        pendingPreviewInsertAt = insertAt
        container.removeCallbacks(applyPendingPreviewOrder)
        container.post(applyPendingPreviewOrder)
    }

    fun cancelScheduledPreviewOrder() {
        dashboardContainer()?.let { container ->
            container.removeCallbacks(applyPendingPreviewOrder)
            container.removeCallbacks(refreshDragLocationRunnable)
        }
        pendingPreviewCard = null
        pendingPreviewOrder = null
        pendingPreviewInsertAt = -1
        pendingDragLocationRefresh = false
        endDashboardDragSession()
    }

    fun applyPreviewOrder(card: DashboardCardType, order: List<String>, insertAt: Int) {
        val container = dashboardContainer() ?: return
        val placeholder = editModeController.dragPlaceholderView ?: FrameLayout(activity).apply {
            tag = DRAG_PLACEHOLDER_TAG
            clipChildren = true
            elevation = activity.dp(4).toFloat()
            addView(createDropPlaceholder(card), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            setOnDragListener(createDashboardDragListener(handleFinish = false))
        }.also { editModeController.dragPlaceholderView = it }

        val existingParent = placeholder.parent as? ViewGroup
        val inContainer = existingParent === container
        val previousIndex = if (inContainer) container.indexOfChild(placeholder) else -1
        val draggedId = editModeController.draggedCardId ?: card.id
        val remainingOrder = order.filterNot { it == draggedId }
        val beforeId = remainingOrder.getOrNull(insertAt)
        val childCountBeforeRemoval = container.childCount
        val bottomSlotPresent = container.childWithTag(EDIT_MODE_BOTTOM_SLOT_TAG) != null
        val trailingSlots = (if (inContainer) 1 else 0) + if (bottomSlotPresent) 1 else 0
        val targetIndex = beforeId?.let { id ->
            var index = cardViewById(id)?.let { container.indexOfChild(it) } ?: -1
            if (inContainer && index > previousIndex) index--
            index
        }?.takeIf { it >= 0 }
            ?: (childCountBeforeRemoval - trailingSlots)
        val clampedIndex = targetIndex.coerceIn(
            0,
            (childCountBeforeRemoval - trailingSlots).coerceAtLeast(0),
        )
        if (inContainer && previousIndex == clampedIndex) return

        container.suppressLayout(true)
        try {
            if (inContainer) {
                container.removeView(placeholder)
            } else {
                existingParent?.removeView(placeholder)
            }
            container.addView(
                placeholder,
                clampedIndex,
                dashboardCardLayoutParams(card, dragPlaceholderHeight(card)),
            )
        } finally {
            container.suppressLayout(false)
        }
        container.postOnAnimation {
            scheduleRefreshDashboardDragLocation()
        }
        if (insertAt >= remainingOrder.size) {
            dragDashboardScroll?.post {
                dragDashboardScroll?.smoothScrollTo(0, container.bottom)
            }
        }
    }

    private fun dragPlaceholderHeight(card: DashboardCardType): Int? =
        editModeController.dragSourceHeight.takeIf { it > 0 }
            ?: activity.dp(card.size.heightDp)

    private fun usesLightDragShadow(card: DashboardCardType): Boolean =
        card in POST_CARD_TYPES ||
            card == DashboardCardType.TOP_FOLLOWERS ||
            card.size == DashboardCardSize.CHART

    private fun dashboardContainer(): GridLayout? =
        dragDashboardContainer ?: activity.findViewById(R.id.dashboard_content)

    private fun cardViewById(id: String): View? =
        dragCardViewCache?.get(id) ?: dashboardContainer()?.childWithTag(id)

    fun settleDashboardDrag(@Suppress("UNUSED_PARAMETER") order: List<String>) {
        val container = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        val placeholder = editModeController.dragPlaceholderView ?: return
        val source = editModeController.dragSourceView ?: return
        val placeholderIndex = container.indexOfChild(placeholder)
        if (placeholderIndex == -1) return
        val sourceLayoutParams = editModeController.dragSourceLayoutParams ?: source.layoutParams
        container.removeView(placeholder)
        if (source.parent === container) {
            container.removeView(source)
        }
        val settledIndex = placeholderIndex.coerceIn(0, indexBeforeEditModeBottomSlot(container))
        source.alpha = 0.82f
        source.scaleX = 0.98f
        source.scaleY = 0.98f
        container.addView(source, settledIndex, sourceLayoutParams)
        source.animate()
            .alpha(0.96f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DASHBOARD_MOVE_DURATION_MS)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()
        lastDragScreenX = Float.NaN
        lastDragScreenY = Float.NaN
        lastRefreshScreenX = Float.NaN
        lastRefreshScreenY = Float.NaN
    }

    private fun ViewGroup.childIndexWithTag(tagValue: String): Int {
        for (index in 0 until childCount) {
            if (getChildAt(index).tag == tagValue) return index
        }
        return -1
    }

    private fun ViewGroup.childWithTag(tagValue: String): View? {
        val index = childIndexWithTag(tagValue)
        return if (index >= 0) getChildAt(index) else null
    }

    private fun indexBeforeEditModeBottomSlot(container: ViewGroup): Int {
        val bottomSlotIndex = container.childIndexWithTag(EDIT_MODE_BOTTOM_SLOT_TAG)
        return if (bottomSlotIndex >= 0) bottomSlotIndex else container.childCount
    }

    private fun ensureEditModeBottomSlot(container: GridLayout) {
        if (container.childWithTag(EDIT_MODE_BOTTOM_SLOT_TAG) != null) return
        container.addView(createEditModeBottomSlot(), editModeBottomSlotLayoutParams())
    }

    private fun createEditModeBottomSlot(): View =
        View(activity).apply {
            tag = EDIT_MODE_BOTTOM_SLOT_TAG
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isClickable = false
            setOnDragListener(createDashboardDragListener(handleFinish = false))
        }

    private fun editModeBottomSlotLayoutParams(): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = activity.dp(DashboardCardSize.HALF.heightDp)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            setMargins(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(5))
        }

    private fun dashboardCardLayoutParams(card: DashboardCardType, dragHeight: Int? = null): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = when {
                dragHeight != null && dragHeight > 0 -> dragHeight
                else -> activity.dp(card.size.heightDp)
            }
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, card.size.span, 1f)
            setMargins(activity.dp(5), activity.dp(5), activity.dp(5), activity.dp(5))
        }

    private fun createDashboardDragListener(handleFinish: Boolean): View.OnDragListener =
        View.OnDragListener { source, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED ->
                    editModeController.editMode && (event.localState as? String) != null
                DragEvent.ACTION_DRAG_LOCATION -> {
                    handleDashboardDragLocation(source, event)
                    true
                }
                DragEvent.ACTION_DROP -> true
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (handleFinish) {
                        editModeController.finishDashboardDrag(commit = event.result)
                    }
                    true
                }
                else -> true
            }
        }

    private fun decimal(value: Double, suffix: String): String =
        String.format(Locale.US, "%.1f%s", value, suffix)

    private class CardDragGesture {
        var downRawX = 0f
        var downRawY = 0f
        var downLocalX = 0f
        var downLocalY = 0f
        var armed = false
        var started = false
        var suppressClick = false
        var longPressPosted = false
        var longPressRunnable: Runnable? = null
    }

    private class AccentCardDragShadowBuilder(
        activity: MainActivity,
        label: String,
        source: View,
        touchPointX: Float,
        touchPointY: Float,
    ) : View.DragShadowBuilder() {
        private val fallback = AccentDragShadowFallback(
            activity = activity,
            label = label,
            sourceWidth = source.width.coerceAtLeast(1),
            sourceHeight = source.height.coerceAtLeast(1),
            touchPointX = touchPointX,
            touchPointY = touchPointY,
        )

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            fallback.provideShadowMetrics(outShadowSize, outShadowTouchPoint)
        }

        override fun onDrawShadow(canvas: Canvas) {
            fallback.drawShadow(canvas)
        }
    }

    private class CachedCardDragShadowBuilder(
        activity: MainActivity,
        private val label: String,
        source: View,
        private val touchPointX: Float,
        private val touchPointY: Float,
    ) : View.DragShadowBuilder() {
        private val snapshotWidth = source.width.coerceAtLeast(1)
        private val snapshotHeight = source.height.coerceAtLeast(1)
        private val horizontalInset = (snapshotWidth * 0.02f).toInt()
        private val verticalInset = (snapshotHeight * 0.02f).toInt()
        private val shadowWidth = snapshotWidth + horizontalInset * 2
        private val shadowHeight = snapshotHeight + verticalInset * 2
        private val shadowOffset = activity.dp(2).toFloat()
        private val cornerRadius = activity.dp(22).toFloat()
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(20, 0, 0, 0)
        }
        private val bitmap: Bitmap? = runCatching {
            Bitmap.createBitmap(snapshotWidth, snapshotHeight, Bitmap.Config.RGB_565).also { captured ->
                Canvas(captured).apply {
                    source.draw(this)
                }
            }
        }.getOrNull()
        private val fallback = if (bitmap == null) {
            AccentDragShadowFallback(
                activity = activity,
                label = label,
                sourceWidth = snapshotWidth,
                sourceHeight = snapshotHeight,
                touchPointX = touchPointX,
                touchPointY = touchPointY,
            )
        } else {
            null
        }

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            if (fallback != null) {
                fallback.provideShadowMetrics(outShadowSize, outShadowTouchPoint)
                return
            }
            outShadowSize.set(shadowWidth, shadowHeight)
            outShadowTouchPoint.set(
                (touchPointX + horizontalInset).toInt().coerceIn(0, outShadowSize.x),
                (touchPointY + verticalInset).toInt().coerceIn(0, outShadowSize.y),
            )
        }

        override fun onDrawShadow(canvas: Canvas) {
            val captured = bitmap
            if (captured != null) {
                val left = horizontalInset.toFloat()
                val top = verticalInset + shadowOffset
                canvas.drawRoundRect(
                    RectF(left, top, left + snapshotWidth, top + snapshotHeight),
                    cornerRadius,
                    cornerRadius,
                    shadowPaint,
                )
                canvas.save()
                canvas.translate(horizontalInset.toFloat(), verticalInset.toFloat())
                canvas.scale(1.02f, 1.02f, snapshotWidth / 2f, snapshotHeight / 2f)
                canvas.drawBitmap(captured, 0f, 0f, null)
                canvas.restore()
            } else {
                fallback?.drawShadow(canvas)
            }
        }
    }

    private class AccentDragShadowFallback(
        activity: MainActivity,
        private val label: String,
        sourceWidth: Int,
        sourceHeight: Int,
        private val touchPointX: Float,
        private val touchPointY: Float,
    ) {
        private val snapshotWidth = sourceWidth
        private val snapshotHeight = sourceHeight
        private val horizontalInset = (sourceWidth * 0.02f).toInt()
        private val verticalInset = (sourceHeight * 0.02f).toInt()
        private val shadowWidth = sourceWidth + horizontalInset * 2
        private val shadowHeight = sourceHeight + verticalInset * 2
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activity.oneUiCardBackground()
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activity.oneUiAccent()
            style = Paint.Style.STROKE
            strokeWidth = activity.dp(2).toFloat()
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activity.oneUiTextSecondary()
            textSize = 13f * activity.resources.displayMetrics.scaledDensity
            typeface = Typeface.create("sec", Typeface.BOLD)
        }

        fun provideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            outShadowSize.set(shadowWidth, shadowHeight)
            outShadowTouchPoint.set(
                (touchPointX + horizontalInset).toInt().coerceIn(0, outShadowSize.x),
                (touchPointY + verticalInset).toInt().coerceIn(0, outShadowSize.y),
            )
        }

        fun drawShadow(canvas: Canvas) {
            val left = horizontalInset.toFloat()
            val top = verticalInset.toFloat()
            val right = left + snapshotWidth
            val bottom = top + snapshotHeight
            val radius = 22f
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, cardPaint)
            canvas.drawRoundRect(RectF(left, top, right, bottom), radius, radius, strokePaint)
            canvas.drawText(label, left + 16f, top + 28f, labelPaint)
        }
    }

    private companion object {
        private const val DASHBOARD_GRID_COLUMNS = 2
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val DASHBOARD_MOVE_DURATION_MS = 220L
        private const val DRAG_START_SLOP_DP = 8
        private const val DRAG_SLOT_HYSTERESIS_DP = 12
        private const val DRAG_BOTTOM_GUTTER_DP = 24
        private const val DRAG_LOCATION_MIN_STEP_DP = 4
        private const val DRAG_PLACEHOLDER_TAG = "dashboard_drop_placeholder"
        private const val REMOVE_BUTTON_TAG = "dashboard_remove_button"
        private const val EDIT_MODE_TOUCH_SHIELD_TAG = "dashboard_edit_touch_shield"
        private const val EDIT_MODE_BOTTOM_SLOT_TAG = "dashboard_edit_bottom_slot"
        private const val EDIT_DRAG_LONG_PRESS_MS = 350L
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
