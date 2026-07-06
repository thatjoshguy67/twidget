package com.tjg.twidget

import android.appwidget.AppWidgetManager
import android.animation.LayoutTransition
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.oneuiproject.oneui.layout.DrawerLayout
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import dev.oneuiproject.oneui.navigation.widget.DrawerNavigationView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import dev.oneuiproject.oneui.design.R as OneUiDesignR
import dev.oneuiproject.oneui.R as OneUiIconR

class MainActivity : AppCompatActivity() {
    private var isSyncing = false
    private var accounts = emptyList<String>()
    private var selectedAccount: String = ""
    private var selectedRange: HistoryRange = HistoryRange.WEEK
    private var editMode = false
    private var draggedCardId: String? = null
    private var dragPreviewOrder: List<String>? = null
    private var dragPlaceholderView: View? = null
    private var dragSourceView: View? = null
    private val drawerAccountItemIds = mutableMapOf<Int, String>()
    private val drawerAvatarItemIds = mutableSetOf<Int>()
    private val downloadingDrawerAvatarUrls = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TwidgetStore.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        RefreshWorker.schedule(this)
        setupRefresh()
        setupDrawerChrome()
        render()
        if (TwidgetStore.settings(this).refreshOnLaunch) {
            sync()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_add_widget)?.isVisible = !editMode
        menu.findItem(R.id.menu_add_card)?.isVisible = editMode
        menu.findItem(R.id.menu_done_editing)?.isVisible = editMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_add_widget) {
            requestWidgetPin()
            return true
        }
        if (item.itemId == R.id.menu_add_card) {
            showAddCardDialog()
            return true
        }
        if (item.itemId == R.id.menu_done_editing) {
            setEditMode(false)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (editMode) {
            setEditMode(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun render() {
        accounts = TwidgetStore.accounts(this)
            .ifEmpty { listOf(TwidgetStore.settings(this).username) }
            .filter { it.isNotBlank() }
        if (accounts.isEmpty()) return
        if (selectedAccount.isBlank() || accounts.none { it.equals(selectedAccount, ignoreCase = true) }) {
            selectedAccount = accounts.first()
        }
        bindContent()
        buildDrawer()
        renderHeader()
    }

    private fun bindContent() {
        val host = findViewById<FrameLayout>(R.id.main_content_host)
        val page = host.getChildAt(0)
            ?: LayoutInflater.from(this).inflate(R.layout.main_account_page, host, false)
                .also { host.addView(it) }
        buildRangeSelector(page)
        bindPage(page, selectedAccount)
    }

    private fun buildRangeSelector(page: View) {
        val row = page.findViewById<LinearLayout>(R.id.range_selector) ?: return
        row.removeAllViews()
        HistoryRange.entries.forEach { range ->
            val selected = range == selectedRange
            val tab = TextView(this).apply {
                text = getString(range.labelRes)
                gravity = android.view.Gravity.CENTER
                textSize = 14f
                minHeight = dp(38)
                minWidth = dp(58)
                setPadding(dp(18), 0, dp(18), 0)
                typeface = android.graphics.Typeface.create("sec", android.graphics.Typeface.BOLD)
                setTextColor(if (selected) Color.WHITE else getColor(R.color.oneui_text_primary))
                background = rangeChipBackground(selected)
                isSelected = selected
                setOnClickListener {
                    if (selectedRange != range) {
                        selectedRange = range
                        clearDragPreview()
                        buildRangeSelector(page)
                        bindPage(page, selectedAccount)
                    }
                }
            }
            row.addView(tab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = if (row.childCount == 0) 0 else dp(8)
            })
        }
    }

    private fun rangeChipBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (selected) {
                setColor(getColor(R.color.oneui_accent))
            } else {
                setColor(getColor(R.color.oneui_card_bg))
                setStroke(dp(1), getColor(R.color.oneui_divider))
            }
        }

    private fun bindPage(page: View, account: String) {
        val stats = TwidgetStore.currentStats(this, account)
        val history = TwidgetStore.rangedHistory(this, account, selectedRange)
        val container = page.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        container.columnCount = DASHBOARD_GRID_COLUMNS
        container.layoutTransition = LayoutTransition().apply {
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(140)
        }
        container.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> editMode && (event.localState as? String) != null
                DragEvent.ACTION_DROP -> {
                    finishDashboardDrag(commit = true)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    finishDashboardDrag(commit = false)
                    true
                }
                else -> true
            }
        }
        container.removeAllViews()

        TwidgetStore.dashboardCards(this)
            .mapNotNull(DashboardCardType::fromId)
            .forEach { card ->
                val content = if (card.size == DashboardCardSize.CHART) {
                    createChartCard(card, stats, history)
                } else {
                    createInsightCard(card, stats, history)
                }
                val wrapper = createDashboardCardWrapper(card, content)
                container.addView(wrapper, dashboardCardLayoutParams(card))
            }
    }

    // Net change across the whole visible range (last bucket minus first).
    private fun rangeDelta(history: List<HistorySample>, selector: (HistorySample) -> Long): Long {
        if (history.size < 2) return 0
        return selector(history.last()) - selector(history.first())
    }

    private fun createInsightCard(card: DashboardCardType, stats: ProfileStats, history: List<HistorySample>): View {
        val spec = insightSpec(card, stats, history)
        val valueTextSize = when (card.size) {
            DashboardCardSize.LARGE -> 34f
            DashboardCardSize.MEDIUM -> 28f
            else -> 20f
        }
        val labelTextSize = if (card.size == DashboardCardSize.SMALL) 11f else 13f
        val detailTextSize = if (card.size == DashboardCardSize.SMALL) 11f else 14f
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = AppCompatResources.getDrawable(this@MainActivity, R.drawable.metric_card_bg)

            val labelRow = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            labelRow.addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(spec.accent)
                }
            }, LinearLayout.LayoutParams(dp(8), dp(8)))
            labelRow.addView(TextView(this@MainActivity).apply {
                text = spec.label
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.oneui_text_secondary))
                textSize = labelTextSize
                typeface = Typeface.create("sec", Typeface.BOLD)
                setPadding(dp(6), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(labelRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            addView(TextView(this@MainActivity).apply {
                text = spec.value
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.oneui_text_primary))
                textSize = valueTextSize
                typeface = Typeface.create("sec", Typeface.BOLD)
                setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            if (spec.progress != null) {
                addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = spec.progress.coerceIn(0, 100)
                    progressTintList = ColorStateList.valueOf(spec.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.oneui_divider))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(6),
                ).apply {
                    topMargin = dp(9)
                })
            }

            addView(TextView(this@MainActivity).apply {
                text = spec.detail
                includeFontPadding = false
                maxLines = if (spec.progress == null) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.oneui_text_secondary))
                textSize = detailTextSize
                setPadding(0, dp(7), 0, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun createChartCard(card: DashboardCardType, stats: ProfileStats, history: List<HistorySample>): View {
        val (layoutRes, valueId, deltaId, chartId, value, selector) = when (card) {
            DashboardCardType.FOLLOWERS -> ChartBinding(
                R.layout.metric_card_followers,
                R.id.followers_value,
                R.id.followers_delta,
                R.id.followers_chart,
                TwidgetStore.compactNumber(stats.followersCount),
            ) { it.followers }
            DashboardCardType.FOLLOWING -> ChartBinding(
                R.layout.metric_card_following,
                R.id.following_value,
                R.id.following_delta,
                R.id.following_chart,
                TwidgetStore.compactNumber(stats.followingsCount),
            ) { it.following }
            DashboardCardType.POSTS -> ChartBinding(
                R.layout.metric_card_posts,
                R.id.posts_value,
                R.id.posts_delta,
                R.id.posts_chart,
                TwidgetStore.compactNumber(stats.statusesCount),
            ) { it.posts }
            DashboardCardType.LIKES -> ChartBinding(
                R.layout.metric_card_likes,
                R.id.likes_value,
                R.id.likes_delta,
                R.id.likes_chart,
                TwidgetStore.compactNumber(stats.likeCount),
            ) { it.likes }
            else -> error("Compact cards do not have chart layouts.")
        }
        return LayoutInflater.from(this).inflate(layoutRes, null, false).also {
            bindMetric(it, valueId, deltaId, chartId, value, rangeDelta(history, selector), history, selector)
        }
    }

    private fun createDropPlaceholder(card: DashboardCardType): View =
        FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), getColor(R.color.oneui_accent), dp(10).toFloat(), dp(6).toFloat())
            }
            alpha = 0.75f
            contentDescription = getString(card.labelRes)
        }

    private fun createDashboardCardWrapper(card: DashboardCardType, content: View): FrameLayout =
        FrameLayout(this).apply {
            tag = card.id
            val longPressHandler = View.OnLongClickListener {
                handleDashboardCardLongPress(card, this)
            }
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            alpha = if (editMode) 0.96f else 1f
            setOnLongClickListener(longPressHandler)
            attachCardLongPress(content, longPressHandler)
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> editMode && (event.localState as? String) != null
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        val dragged = event.localState as? String ?: draggedCardId
                        if (editMode && dragged != null && dragged != card.id) {
                            animate().scaleX(0.98f).scaleY(0.98f).setDuration(80).start()
                            previewMoveDashboardCard(dragged, card.id)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        finishDashboardDrag(commit = true)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        finishDashboardDrag(commit = false)
                        animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        true
                    }
                    else -> true
                }
            }
            if (editMode) {
                addView(removeCardButton(card), FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(6)
                    marginEnd = dp(6)
                })
            }
        }

    private fun handleDashboardCardLongPress(card: DashboardCardType, dragView: View): Boolean {
        if (!editMode) {
            setEditMode(true)
        } else {
            draggedCardId = card.id
            dragPreviewOrder = TwidgetStore.dashboardCards(this)
            dragSourceView = dragView
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
                finishDashboardDrag(commit = false)
            }
        }
        return true
    }

    private fun attachCardLongPress(view: View, listener: View.OnLongClickListener) {
        view.setOnLongClickListener(listener)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachCardLongPress(view.getChildAt(index), listener)
            }
        }
    }

    private fun removeCardButton(card: DashboardCardType): ImageButton =
        ImageButton(this).apply {
            setImageResource(OneUiIconR.drawable.ic_oui_remove)
            imageTintList = ColorStateList.valueOf(getColor(R.color.metric_red))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.oneui_card_bg))
                setStroke(dp(1), getColor(R.color.oneui_divider))
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = getString(R.string.delete)
            setOnClickListener { removeDashboardCard(card.id) }
        }

    private fun bindMetric(
        root: View,
        valueId: Int,
        deltaId: Int,
        chartId: Int,
        value: String,
        delta: Long,
        history: List<HistorySample>,
        selector: (HistorySample) -> Long,
    ) {
        root.findViewById<TextView>(valueId)?.text = value
        root.findViewById<TextView>(deltaId)?.apply {
            text = if (delta == 0L) "" else TwidgetStore.signedNumber(delta)
            setTextColor(if (delta < 0) getColor(R.color.metric_red) else getColor(R.color.metric_green))
            visibility = if (delta == 0L) View.GONE else View.VISIBLE
        }
        root.findViewById<MetricChartView>(chartId)?.setData(history, selector)
    }

    private fun insightSpec(card: DashboardCardType, stats: ProfileStats, history: List<HistorySample>): InsightSpec {
        val followersDelta = rangeDelta(history) { it.followers }
        return when (card) {
            DashboardCardType.FOLLOWER_RATIO -> InsightSpec(
                label = getString(R.string.follower_ratio),
                value = decimal(stats.followersCount.toDouble() / stats.followingsCount.coerceAtLeast(1), "x"),
                detail = getString(R.string.more_followers_than_following, TwidgetStore.compactNumber((stats.followersCount - stats.followingsCount).coerceAtLeast(0))),
                accent = getColor(R.color.oneui_accent),
            )
            DashboardCardType.POST_RATE -> InsightSpec(
                label = getString(R.string.post_rate),
                value = decimal(dailyAverage(history) { it.posts }.coerceAtLeast(0.0), ""),
                detail = getString(R.string.per_range, TwidgetStore.signedNumber(rangeDelta(history) { it.posts })),
                accent = getColor(R.color.metric_green),
            )
            DashboardCardType.LIKES_PER_POST -> InsightSpec(
                label = getString(R.string.likes_per_post),
                value = decimal(stats.likeCount.toDouble() / stats.statusesCount.coerceAtLeast(1), ""),
                detail = "${TwidgetStore.compactNumber(stats.likeCount)} ${getString(R.string.likes).lowercase(Locale.US)}",
                accent = getColor(R.color.oneui_accent),
            )
            DashboardCardType.MILESTONE -> {
                val milestone = nextMilestone(stats.followersCount)
                val previous = previousMilestone(milestone)
                val remaining = (milestone - stats.followersCount).coerceAtLeast(0)
                val progress = if (milestone == previous) 100 else (((stats.followersCount - previous).coerceAtLeast(0) * 100) / (milestone - previous)).toInt()
                InsightSpec(
                    label = "Milestone",
                    value = TwidgetStore.compactNumber(milestone),
                    detail = getString(R.string.to_next_milestone, TwidgetStore.compactNumber(remaining), TwidgetStore.compactNumber(milestone)),
                    accent = getColor(R.color.oneui_accent),
                    progress = progress,
                )
            }
            DashboardCardType.GROWTH_PACE -> {
                val daily = dailyAverage(history) { it.followers }
                InsightSpec(
                    label = "Growth",
                    value = TwidgetStore.signedNumber(followersDelta),
                    detail = getString(R.string.per_day, signedDecimal(daily)),
                    accent = if (followersDelta < 0) getColor(R.color.metric_red) else getColor(R.color.metric_green),
                )
            }
            DashboardCardType.BEST_DAY -> {
                val best = bestRecentDay(history)
                InsightSpec(
                    label = "Best day",
                    value = if (best == null) "--" else TwidgetStore.signedNumber(best.second),
                    detail = best?.first ?: getString(R.string.no_recent_gain),
                    accent = getColor(R.color.metric_green),
                )
            }
            DashboardCardType.MOMENTUM -> {
                val momentum = momentum(history)
                InsightSpec(
                    label = getString(R.string.momentum),
                    value = momentum.first,
                    detail = getString(R.string.per_range, TwidgetStore.signedNumber(followersDelta)),
                    accent = momentum.second,
                )
            }
            DashboardCardType.AUDIENCE_BALANCE -> InsightSpec(
                label = "Balance",
                value = "${stats.followersCount.coerceAtLeast(1) / stats.followingsCount.coerceAtLeast(1)}:1",
                detail = "${TwidgetStore.compactNumber(stats.followersCount)} / ${TwidgetStore.compactNumber(stats.followingsCount)}",
                accent = getColor(R.color.oneui_accent),
            )
            DashboardCardType.ACCOUNT_HEALTH -> {
                val title = when {
                    stats.isVerified == true -> getString(R.string.verified)
                    stats.isPrivate == true -> getString(R.string.private_profile)
                    else -> getString(R.string.public_profile)
                }
                InsightSpec(
                    label = "Health",
                    value = title,
                    detail = if (stats.isVerified == true) getString(R.string.verified) else getString(R.string.not_verified),
                    accent = if (stats.isPrivate == true) getColor(R.color.metric_red) else getColor(R.color.metric_green),
                )
            }
            else -> error("Chart cards do not have insight specs.")
        }
    }

    private fun dailyAverage(history: List<HistorySample>, selector: (HistorySample) -> Long): Double {
        if (history.size < 2) return 0.0
        return rangeDelta(history, selector).toDouble() / (history.size - 1).coerceAtLeast(1)
    }

    private fun bestRecentDay(history: List<HistorySample>): Pair<String, Long>? =
        history.zipWithNext()
            .map { (previous, current) -> current.dayLabel to (current.followers - previous.followers) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }

    private fun momentum(history: List<HistorySample>): Pair<String, Int> {
        if (history.size < 4) return getString(R.string.flat) to getColor(R.color.oneui_text_secondary)
        val middle = history.lastIndex / 2
        val firstHalf = history[middle].followers - history.first().followers
        val secondHalf = history.last().followers - history[middle].followers
        val threshold = maxOf(2L, (abs(firstHalf) * 0.25).roundToLong())
        return when {
            secondHalf > firstHalf + threshold -> getString(R.string.accelerating) to getColor(R.color.metric_green)
            secondHalf < firstHalf - threshold -> getString(R.string.cooling) to getColor(R.color.metric_red)
            firstHalf == 0L && secondHalf == 0L -> getString(R.string.flat) to getColor(R.color.oneui_text_secondary)
            else -> "Steady" to getColor(R.color.oneui_accent)
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

    private fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        if (!enabled) clearDragPreview()
        invalidateOptionsMenu()
        render()
    }

    private fun removeDashboardCard(cardId: String) {
        val current = TwidgetStore.dashboardCards(this)
        if (current.size <= 1) {
            Toast.makeText(this, R.string.cannot_remove_last_card, Toast.LENGTH_SHORT).show()
            return
        }
        TwidgetStore.saveDashboardCards(this, current.filterNot { it == cardId })
        render()
    }

    private fun previewMoveDashboardCard(draggedId: String, targetId: String) {
        val cards = (dragPreviewOrder ?: TwidgetStore.dashboardCards(this)).toMutableList()
        val from = cards.indexOf(draggedId)
        val to = cards.indexOf(targetId)
        if (from == -1 || to == -1 || from == to) return
        val moved = cards.removeAt(from)
        cards.add(if (from < to) to - 1 else to, moved)
        if (cards == dragPreviewOrder) return
        dragPreviewOrder = cards
        DashboardCardType.fromId(draggedId)?.let { moveDropPlaceholder(it, targetId) }
    }

    private fun finishDashboardDrag(commit: Boolean) {
        if (draggedCardId == null) return
        if (commit) {
            dragPreviewOrder?.let { TwidgetStore.saveDashboardCards(this, it) }
        }
        clearDragPreview()
        if (commit) render()
    }

    private fun clearDragPreview() {
        dragPlaceholderView?.let { placeholder ->
            (placeholder.parent as? ViewGroup)?.removeView(placeholder)
        }
        dragSourceView?.visibility = View.VISIBLE
        draggedCardId = null
        dragPreviewOrder = null
        dragPlaceholderView = null
        dragSourceView = null
    }

    private fun moveDropPlaceholder(card: DashboardCardType, targetId: String) {
        val container = findViewById<GridLayout>(R.id.dashboard_content) ?: return
        val placeholder = dragPlaceholderView ?: FrameLayout(this).apply {
            tag = DRAG_PLACEHOLDER_TAG
            addView(createDropPlaceholder(card), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> editMode && (event.localState as? String) != null
                    DragEvent.ACTION_DROP -> {
                        finishDashboardDrag(commit = true)
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        finishDashboardDrag(commit = false)
                        true
                    }
                    else -> true
                }
            }
        }.also { dragPlaceholderView = it }

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
        container.addView(placeholder, targetIndex.coerceIn(0, container.childCount), dashboardCardLayoutParams(card))
    }

    private fun ViewGroup.childIndexWithTag(tagValue: String): Int {
        for (index in 0 until childCount) {
            if (getChildAt(index).tag == tagValue) return index
        }
        return -1
    }

    private fun dashboardCardLayoutParams(card: DashboardCardType): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = dp(card.size.heightDp)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, card.size.span, 1f)
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }

    private fun showAddCardDialog() {
        val current = TwidgetStore.dashboardCards(this)
        val hidden = TwidgetStore.DEFAULT_DASHBOARD_CARDS
            .filterNot { it in current }
            .mapNotNull(DashboardCardType::fromId)
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.all_cards_added, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_cards_title)
            .setItems(hidden.map { getString(it.labelRes) }.toTypedArray()) { _, which ->
                TwidgetStore.saveDashboardCards(this, current + hidden[which].id)
                render()
            }
            .show()
    }

    private fun bindChip(chip: TextView?, label: String, color: Int) {
        chip ?: return
        chip.text = label
        val textColor = getColor(R.color.oneui_text_primary)
        chip.setTextColor(textColor)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(34, Color.red(color), Color.green(color), Color.blue(color)))
            setStroke(dp(1), Color.argb(95, Color.red(color), Color.green(color), Color.blue(color)))
        }
    }

    private fun deltaFor(history: List<HistorySample>, selector: (HistorySample) -> Long): Long {
        if (history.size < 2) return 0
        return selector(history.last()) - selector(history[history.lastIndex - 1])
    }

    private fun decimal(value: Double, suffix: String): String =
        String.format(Locale.US, "%.1f%s", value, suffix)

    // --- Drawer ---

    private fun buildDrawer() {
        val drawerNav = findViewById<DrawerNavigationView>(R.id.drawer_nav)
        val menu = drawerNav.drawerMenu()

        drawerAccountItemIds.clear()
        drawerAvatarItemIds.clear()
        menu.clear()
        accounts.forEachIndexed { index, account ->
            val stats = TwidgetStore.currentStats(this, account)
            val itemId = DRAWER_ACCOUNT_ITEM_BASE + index
            drawerAccountItemIds[itemId] = account
            menu.add(
                DRAWER_GROUP_ACCOUNTS,
                itemId,
                index,
                stats.fullName.ifBlank { account },
            ).apply {
                val (icon, isAvatar) = drawerAccountIcon(stats)
                setIcon(icon)
                if (isAvatar) drawerAvatarItemIds += itemId
                isCheckable = true
                isChecked = account.equals(selectedAccount, ignoreCase = true)
                contentDescription = stats.fullName.ifBlank { account }
            }
        }

        menu.setGroupCheckable(DRAWER_GROUP_ACCOUNTS, true, true)
        menu.add(
            DRAWER_GROUP_ACTIONS,
            DRAWER_ITEM_ADD_ACCOUNT,
            accounts.size,
            getString(R.string.add_account),
        ).apply {
            setIcon(OneUiIconR.drawable.ic_oui_add)
            contentDescription = getString(R.string.add_account)
        }
        drawerNav.refreshDrawerMenu()
        drawerNav.post { applyDrawerIconTints(drawerNav) }
    }

    private fun drawerAccountIcon(stats: ProfileStats): Pair<Drawable, Boolean> {
        val iconSize = resources.getDimensionPixelSize(OneUiDesignR.dimen.oui_des_drawer_menu_item_icon_size)
        ProfileImageLoader.cachedCircularBitmap(this, stats.profileImage, iconSize)?.let { bitmap ->
            return BitmapDrawable(resources, bitmap) to true
        }

        queueDrawerAvatarDownload(stats.profileImage)
        val fallback = requireNotNull(AppCompatResources.getDrawable(this, OneUiIconR.drawable.ic_oui_samsung_account))
        return fallback to false
    }

    private fun queueDrawerAvatarDownload(url: String) {
        if (url.isBlank()) return
        synchronized(downloadingDrawerAvatarUrls) {
            if (!downloadingDrawerAvatarUrls.add(url)) return
        }
        Thread {
            val loaded = ProfileImageLoader.downloadToCache(applicationContext, url) != null
            synchronized(downloadingDrawerAvatarUrls) {
                downloadingDrawerAvatarUrls.remove(url)
            }
            if (loaded) {
                runOnUiThread { buildDrawer() }
            }
        }.start()
    }

    private fun applyDrawerIconTints(drawerNav: DrawerNavigationView) {
        val normalTint = AppCompatResources.getColorStateList(
            this,
            OneUiDesignR.color.oui_des_drawer_menu_item_text_color_selector,
        )
        drawerAccountItemIds.keys.forEach { itemId ->
            drawerNav.findViewById<View>(itemId)
                ?.findViewById<ImageView>(OneUiDesignR.id.drawer_menu_item_icon)
                ?.imageTintList = if (itemId in drawerAvatarItemIds) null else normalTint
        }
        drawerNav.findViewById<View>(DRAWER_ITEM_ADD_ACCOUNT)
            ?.findViewById<ImageView>(OneUiDesignR.id.drawer_menu_item_icon)
            ?.imageTintList = normalTint
    }

    private fun handleDrawerItemSelected(item: MenuItem): Boolean {
        drawerAccountItemIds[item.itemId]?.let { account ->
            if (!account.equals(selectedAccount, ignoreCase = true)) {
                selectedAccount = account
                render()
            }
            closeDrawerOnCompactScreens()
            return true
        }

        if (item.itemId == DRAWER_ITEM_ADD_ACCOUNT) {
            closeDrawerOnCompactScreens()
            addAccount()
            return true
        }
        return false
    }

    private fun closeDrawerOnCompactScreens() {
        val drawer = findViewById<NavDrawerLayout>(R.id.main_toolbar_layout)
        if (!drawer.isLargeScreenMode) {
            drawer.setDrawerOpen(false, animate = true)
        }
    }

    private fun DrawerNavigationView.drawerMenu(): Menu {
        val field = DrawerNavigationView::class.java.getDeclaredField("navDrawerMenu")
        field.isAccessible = true
        return field.get(this) as Menu
    }

    private fun DrawerNavigationView.refreshDrawerMenu() {
        val field = DrawerNavigationView::class.java.getDeclaredField("menuPresenter")
        field.isAccessible = true
        val presenter = field.get(this)
        val method = presenter.javaClass.getDeclaredMethod("updateMenuView", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(presenter, false)
    }

    private fun setupDrawerChrome() {
        findViewById<NavDrawerLayout>(R.id.main_toolbar_layout).apply {
            closeNavRailOnBack = true
            setHideNavRailDrawerOnCollapse(false)
        }
        findViewById<DrawerNavigationView>(R.id.drawer_nav)
            .setNavigationItemSelectedListener { item -> handleDrawerItemSelected(item) }
        findViewById<DrawerLayout>(R.id.main_toolbar_layout).setupHeaderButton(
            requireNotNull(AppCompatResources.getDrawable(this, OneUiIconR.drawable.ic_oui_settings_outline)),
            getColor(R.color.oneui_text_secondary),
            getString(R.string.settings),
        ) {
            closeDrawerOnCompactScreens()
            openSettings()
        }
    }

    private fun renderHeader() {
        val stats = TwidgetStore.currentStats(this, selectedAccount)
        findViewById<DrawerLayout>(R.id.main_toolbar_layout).apply {
            if (editMode) {
                setTitle(getString(R.string.edit_home))
                setSubtitle("")
            } else {
                setTitle(stats.fullName.ifBlank { "@${stats.userName}" })
                setSubtitle("@${stats.userName} · ${TwidgetStore.lastSyncedText(this@MainActivity, stats)}")
            }
        }
    }

    private fun setupRefresh() {
        findViewById<SwipeRefreshLayout>(R.id.main_refresh).apply {
            val accent = getColor(R.color.oneui_accent)
            setColorSchemeColors(accent, accent, accent, accent)
            setOnRefreshListener { sync() }
        }
    }

    private fun sync() {
        if (isSyncing) {
            findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing = false
            return
        }
        val account = selectedAccount.ifBlank { TwidgetStore.settings(this).username }
        isSyncing = true
        Thread {
            val result = runCatching {
                val stats = RettiwtClient.refresh(this, account)
                TwidgetStore.saveStats(this, stats)
                TwidgetWidget.updateAll(this)
                stats
            }
            runOnUiThread {
                isSyncing = false
                findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing = false
                render()
                if (result.isFailure) {
                    Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun openSettings() {
        startLeftSidePopOverActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun addAccount() {
        startAddAccountActivity()
    }

    private fun requestWidgetPin() {
        val appWidgetManager = getSystemService(AppWidgetManager::class.java)
        if (!appWidgetManager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.add_widget_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        appWidgetManager.requestPinAppWidget(
            ComponentName(this, TwidgetWidget::class.java),
            null,
            null
        )
        Toast.makeText(this, R.string.add_widget_requested, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class InsightSpec(
        val label: String,
        val value: String,
        val detail: String,
        val accent: Int,
        val progress: Int? = null,
    )

    private data class ChartBinding(
        val layoutRes: Int,
        val valueId: Int,
        val deltaId: Int,
        val chartId: Int,
        val value: String,
        val selector: (HistorySample) -> Long,
    )

    private enum class DashboardCardSize(val span: Int, val heightDp: Int) {
        SMALL(2, 118),
        MEDIUM(3, 140),
        LARGE(6, 156),
        CHART(6, 260),
    }

    private enum class DashboardCardType(val id: String, val labelRes: Int, val size: DashboardCardSize) {
        FOLLOWER_RATIO("follower_ratio", R.string.follower_ratio, DashboardCardSize.SMALL),
        POST_RATE("post_rate", R.string.post_rate, DashboardCardSize.SMALL),
        LIKES_PER_POST("likes_per_post", R.string.likes_per_post, DashboardCardSize.SMALL),
        MILESTONE("milestone", R.string.milestone_progress, DashboardCardSize.LARGE),
        GROWTH_PACE("growth_pace", R.string.growth_pace, DashboardCardSize.MEDIUM),
        BEST_DAY("best_day", R.string.best_recent_day, DashboardCardSize.MEDIUM),
        MOMENTUM("momentum", R.string.momentum, DashboardCardSize.MEDIUM),
        AUDIENCE_BALANCE("audience_balance", R.string.audience_balance, DashboardCardSize.MEDIUM),
        ACCOUNT_HEALTH("account_health", R.string.account_health, DashboardCardSize.MEDIUM),
        FOLLOWERS("followers", R.string.followers, DashboardCardSize.CHART),
        FOLLOWING("following", R.string.following, DashboardCardSize.CHART),
        POSTS("posts", R.string.posts, DashboardCardSize.CHART),
        LIKES("likes", R.string.likes, DashboardCardSize.CHART);

        companion object {
            fun fromId(id: String): DashboardCardType? = entries.firstOrNull { it.id == id }
        }
    }

    private companion object {
        private const val DRAWER_GROUP_ACCOUNTS = 1
        private const val DRAWER_GROUP_ACTIONS = 2
        private const val DRAWER_ACCOUNT_ITEM_BASE = 10_000
        private const val DRAWER_ITEM_ADD_ACCOUNT = 20_000
        private const val DASHBOARD_GRID_COLUMNS = 6
        private const val DRAG_PLACEHOLDER_TAG = "dashboard_drop_placeholder"
    }
}
