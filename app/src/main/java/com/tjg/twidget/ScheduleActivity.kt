package com.tjg.twidget

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.ContextCompat
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.widget.FloatingActionBar
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.widget.RoundedLinearLayout
import dev.oneuiproject.oneui.widget.RoundedNestedScrollView
import dev.oneuiproject.oneui.widget.ScrollAwareFloatingActionButton
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import dev.oneuiproject.oneui.R as OneUiIconR
import dev.oneuiproject.oneui.design.R as OneUiDesignR

class ScheduleActivity : FoldablePopOverActivity() {
    private lateinit var content: LinearLayout
    private lateinit var primaryButton: ScrollAwareFloatingActionButton
    private lateinit var queueRoot: View
    private lateinit var switcherHost: FrameLayout
    private lateinit var queueSwitcher: FloatingActionBar
    private lateinit var scroll: RoundedNestedScrollView
    private lateinit var selectionBar: View
    private lateinit var trashBar: View
    private lateinit var trashRestoreLabel: TextView
    private lateinit var trashDeleteLabel: TextView
    private lateinit var selectionCount: TextView
    private val store by lazy { ScheduleStore(this) }
    private val coordinator by lazy { ScheduleCoordinator(this) }

    private var selectedQueueStatus: ScheduleStatus? = null
    private var selectedQueueView = ScheduleQueueView.LIST
    private var calendarMonth = YearMonth.now()
    private var selectedCalendarDate: LocalDate? = null
    private var busy = false
    private var queueSelectionMode = false
    private val selectedQueueIds = linkedSetOf<String>()
    private var activeQueueMenu: PopupMenu? = null
    private var viewingTrash = false
    private val keepQueueSwitcherInContent: (Float) -> Unit = {
        if (::queueSwitcher.isInitialized) queueSwitcher.translationY = 0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)
        content = findViewById(R.id.schedule_content)
        primaryButton = findViewById(R.id.schedule_primary_button)
        queueRoot = findViewById(R.id.schedule_root)
        switcherHost = findViewById(R.id.schedule_switcher_host)
        scroll = findViewById(R.id.schedule_scroll)
        selectionBar = findViewById(R.id.schedule_selection_bar)
        trashBar = findViewById(R.id.schedule_trash_bar)
        trashRestoreLabel = findViewById(R.id.schedule_trash_restore_label)
        trashDeleteLabel = findViewById(R.id.schedule_trash_delete_label)
        selectionCount = findViewById(R.id.schedule_selection_count)
        setupQueueSelectionBar()
        setupTrashBar()
        setupQueueViewSwitcher()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    queueSelectionMode -> exitQueueSelection()
                    viewingTrash -> exitTrashView()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        findViewById<ToolbarLayout>(R.id.schedule_root)
            .setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(findViewById(R.id.schedule_root)) { inset ->
            primaryButton.updateBottomMarginForNavigationBar(dp(20), inset)
            selectionBar.updateBottomMarginForNavigationBar(dp(20), inset)
            trashBar.updateBottomMarginForNavigationBar(dp(20), inset)
        }

        val scheduleId = intent.getStringExtra(ScheduleDeepLink.EXTRA_SCHEDULE_ID)
        val post = scheduleId?.let(store::get)
        when {
            intent.action == ScheduleDeepLink.ACTION_OPEN_CHECKLIST ||
                post?.status == ScheduleStatus.NEEDS_ACTION -> {
                if (post == null) {
                    toast(R.string.schedule_not_found)
                    renderQueue()
                } else {
                    renderChecklist(post)
                }
            }
            post != null && post.deletedAt != null -> enterTrashView()
            post != null -> openEditor(post)
            intent.getBooleanExtra(EXTRA_OPEN_TRASH, false) -> enterTrashView()
            else -> renderQueue()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onRestart() {
        super.onRestart()
        renderQueue()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.schedule, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.schedule_trash_menu)?.isVisible = !queueSelectionMode
        val filterGroup = menu.findItem(R.id.schedule_filter_all_menu)?.groupId ?: 0
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            if (item.groupId == filterGroup) {
                item.isVisible = !viewingTrash
            }
        }
        if (!viewingTrash) {
            val checked = when (selectedQueueStatus) {
                ScheduleStatus.SCHEDULED -> R.id.schedule_filter_scheduled_menu
                ScheduleStatus.DRAFT -> R.id.schedule_filter_drafts_menu
                ScheduleStatus.NEEDS_ACTION -> R.id.schedule_filter_action_menu
                ScheduleStatus.FAILED -> R.id.schedule_filter_failed_menu
                else -> R.id.schedule_filter_all_menu
            }
            menu.findItem(checked)?.isChecked = true
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.schedule_trash_menu) {
            if (viewingTrash) {
                exitTrashView()
            } else {
                enterTrashView()
            }
            return true
        }
        if (viewingTrash) return super.onOptionsItemSelected(item)
        selectedQueueStatus = when (item.itemId) {
            R.id.schedule_filter_all_menu -> null
            R.id.schedule_filter_scheduled_menu -> ScheduleStatus.SCHEDULED
            R.id.schedule_filter_drafts_menu -> ScheduleStatus.DRAFT
            R.id.schedule_filter_action_menu -> ScheduleStatus.NEEDS_ACTION
            R.id.schedule_filter_failed_menu -> ScheduleStatus.FAILED
            else -> return super.onOptionsItemSelected(item)
        }
        exitQueueSelection()
        item.isChecked = true
        renderQueue()
        return true
    }

    private fun setupTrashBar() {
        findViewById<View>(R.id.schedule_trash_restore).setOnClickListener {
            restoreSelectedTrash()
        }
        findViewById<View>(R.id.schedule_trash_delete).setOnClickListener {
            permanentlyDeleteSelectedTrash()
        }
    }

    private fun enterTrashView() {
        exitQueueSelection()
        viewingTrash = true
        selectedQueueView = ScheduleQueueView.LIST
        findViewById<ToolbarLayout>(R.id.schedule_root).setTitle(getString(R.string.schedule_trash))
        invalidateOptionsMenu()
        renderQueue()
    }

    private fun exitTrashView() {
        if (!viewingTrash) return
        exitQueueSelection()
        viewingTrash = false
        findViewById<ToolbarLayout>(R.id.schedule_root).setTitle(getString(R.string.schedule_title))
        invalidateOptionsMenu()
        renderQueue()
    }

    private fun currentQueuePosts(): List<ScheduledPost> {
        val username = requestedUsername()
        return if (viewingTrash) {
            if (username.isBlank()) store.listTrash() else store.listTrashForAccount(username)
        } else {
            val allPosts = if (username.isBlank()) store.list() else store.listForAccount(username)
            allPosts.filter { selectedQueueStatus == null || it.status == selectedQueueStatus }
        }
    }
    private fun setupQueueSelectionBar() {
        findViewById<AppCompatButton>(R.id.schedule_selection_pin).setOnClickListener {
            val drafts = selectedQueuePosts().filter { it.status == ScheduleStatus.DRAFT }
            if (drafts.isEmpty()) return@setOnClickListener
            bulkSetPinned(selected = !drafts.all { it.pinned })
        }
        findViewById<AppCompatButton>(R.id.schedule_selection_delete).setOnClickListener {
            bulkDeleteSelected()
        }
        findViewById<AppCompatButton>(R.id.schedule_selection_cancel).setOnClickListener {
            exitQueueSelection()
        }
    }

    private fun renderQueue() {
        showQueueMode()
        content.removeAllViews()
        val posts = currentQueuePosts()
        val calendarMode = !viewingTrash && selectedQueueView == ScheduleQueueView.CALENDAR
        switcherHost.visibility = if (viewingTrash) View.GONE else View.VISIBLE
        scroll.setPadding(
            scroll.paddingLeft,
            scroll.paddingTop,
            scroll.paddingRight,
            if (calendarMode) 0 else if (viewingTrash) dp(88) else dp(104),
        )
        scroll.isVerticalScrollBarEnabled = !calendarMode
        scroll.overScrollMode = if (calendarMode) {
            View.OVER_SCROLL_NEVER
        } else {
            View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        content.setPadding(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            if (calendarMode) 0 else dp(24),
        )
        if (calendarMode) scroll.scrollTo(0, 0)
        if (calendarMode) {
            renderCalendar(posts)
        } else if (posts.isEmpty()) {
            content.addView(emptyState(viewingTrash))
        } else {
            if (viewingTrash) {
                content.addView(metaText(getString(R.string.schedule_trash_retention)).apply {
                    setPadding(dp(12), dp(4), dp(12), dp(8))
                })
            }
            posts.forEach { content.addView(queueCard(it)) }
        }
        updateQueueSelectionUi(calendarMode)
        TwidgetFonts.applyTo(content)
    }

    private fun updateQueueSelectionUi(calendarMode: Boolean) {
        val showTrashBar = viewingTrash && !calendarMode
        val showSelection = !viewingTrash && queueSelectionMode && !calendarMode
        selectionBar.visibility = if (showSelection) View.VISIBLE else View.GONE
        trashBar.visibility = if (showTrashBar) View.VISIBLE else View.GONE
        if (showTrashBar) {
            val selected = selectedQueueIds.size
            val total = currentQueuePosts().size
            val allSelected = total > 0 && selected == total
            trashRestoreLabel.text = if (allSelected) {
                getString(R.string.schedule_restore_all)
            } else {
                getString(R.string.schedule_restore)
            }
            trashDeleteLabel.text = if (allSelected) {
                getString(R.string.schedule_delete_all)
            } else {
                getString(R.string.delete)
            }
            findViewById<View>(R.id.schedule_trash_restore).isEnabled = selected > 0
            findViewById<View>(R.id.schedule_trash_delete).isEnabled = selected > 0
        }
        selectionCount.text = getString(R.string.schedule_selection_count, selectedQueueIds.size)
        val drafts = selectedQueuePosts().filter { it.status == ScheduleStatus.DRAFT }
        findViewById<AppCompatButton>(R.id.schedule_selection_pin).apply {
            isEnabled = drafts.isNotEmpty()
            text = if (drafts.isNotEmpty() && drafts.all { it.pinned }) {
                getString(R.string.schedule_unpin)
            } else {
                getString(R.string.schedule_pin)
            }
        }
        findViewById<AppCompatButton>(R.id.schedule_selection_delete).isEnabled =
            selectedQueueIds.isNotEmpty()
        primaryButton.apply {
            visibility = if (showSelection || showTrashBar || calendarMode || viewingTrash) {
                View.GONE
            } else {
                View.VISIBLE
            }
            isEnabled = !busy
            contentDescription = getString(R.string.schedule_new)
            setOnClickListener { openEditor(null) }
        }
    }

    private fun setupQueueViewSwitcher() {
        queueSwitcher = FloatingActionBar(this).apply {
            elevation = 0f
            translationZ = 0f
            setButtonLabel(0, getString(R.string.schedule_view_list))
            setButtonLabel(1, getString(R.string.schedule_view_calendar))
            setButtonIcon(0, ContextCompat.getDrawable(context, OneUiIconR.drawable.ic_oui_list))
            setButtonIcon(1, ContextCompat.getDrawable(context, OneUiIconR.drawable.ic_oui_calendar))
            post {
                this@ScheduleActivity.findViewById<ToolbarLayout>(R.id.schedule_root)
                    .addOnBottomOffsetChangedListener(keepQueueSwitcherInContent)
                translationY = 0f
                if (selectedQueueView == ScheduleQueueView.CALENDAR) setSelectedButton(1)
                updateQueueSwitcherBackingLabels()
                setOnButtonSelectedListener { index ->
                    val next = if (index == 0) ScheduleQueueView.LIST else ScheduleQueueView.CALENDAR
                    if (next == selectedQueueView) return@setOnButtonSelectedListener
                    exitQueueSelection()
                    selectedQueueView = next
                    selectedCalendarDate = null
                    updateQueueSwitcherBackingLabels()
                    animateQueueModeChange(if (index == 0) -1 else 1)
                }
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        switcherHost.addView(queueSwitcher)
    }

    private fun updateQueueSwitcherBackingLabels() {
        queueSwitcher.findViewById<View>(OneUiDesignR.id.text1).alpha =
            if (queueSwitcher.selectedIndex == 0) 0f else 1f
        queueSwitcher.findViewById<View>(OneUiDesignR.id.text2).alpha =
            if (queueSwitcher.selectedIndex == 1) 0f else 1f
    }

    private fun animateQueueModeChange(direction: Int) {
        val travel = dp(18).toFloat() * direction
        content.animate().cancel()
        content.animate()
            .alpha(0f)
            .translationX(-travel)
            .setDuration(110)
            .withEndAction {
                renderQueue()
                content.translationX = travel
                content.animate().alpha(1f).translationX(0f).setDuration(170).start()
            }
            .start()
    }

    private fun renderCalendar(posts: List<ScheduledPost>) {
        val zoneId = ZoneId.systemDefault()
        content.addView(calendarHeader())
        content.addView(calendarGrid(posts, zoneId))
        if (scroll.height == 0) {
            scroll.post {
                if (!isFinishing && selectedQueueView == ScheduleQueueView.CALENDAR) renderQueue()
            }
        }
    }

    private fun calendarHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(actionButton("‹") {
            changeCalendarMonth(-1)
        }.apply { contentDescription = getString(R.string.schedule_previous_month) })
        addView(TextView(this@ScheduleActivity).apply {
            text = calendarMonth.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())).uppercase(Locale.getDefault())
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = TwidgetFonts.oneUiSans(context, 700)
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(actionButton("›") {
            changeCalendarMonth(1)
        }.apply { contentDescription = getString(R.string.schedule_next_month) })
    }

    private fun calendarGrid(posts: List<ScheduledPost>, zoneId: ZoneId): View = GridLayout(this).apply {
        columnCount = 7
        setPadding(dp(8), dp(4), dp(8), dp(12))
        val firstDay = calendarMonth.atDay(1)
        val leadingDays = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        val rowCount = (leadingDays + calendarMonth.lengthOfMonth() + 6) / 7
        val viewportHeight = scroll.height.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - dp(220))
        val gridPadding = dp(16)
        val weekHeaderHeight = dp(28)
        val rowMargins = dp(4) * (rowCount + 1)
        val dayHeight = ((
            viewportHeight - dp(58) - content.paddingTop - content.paddingBottom -
                gridPadding - weekHeaderHeight - rowMargins
            ) / rowCount).coerceAtLeast(dp(44))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            gridPadding + weekHeaderHeight + rowMargins + dayHeight * rowCount,
        )
        DayOfWeek.entries.forEachIndexed { column, day ->
            addView(TextView(this@ScheduleActivity).apply {
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = TwidgetFonts.oneUiSans(context, 700)
                setTextColor(ContextCompat.getColor(context, R.color.oneui_text_secondary))
            }, calendarCellParams(0, column, dp(28)))
        }
        repeat(leadingDays) { position ->
            addView(calendarDayCell(null, emptyList()), calendarCellParams(position / 7 + 1, position % 7, dayHeight))
        }
        repeat(calendarMonth.lengthOfMonth()) { offset ->
            val date = calendarMonth.atDay(offset + 1)
            val position = leadingDays + offset
            val row = position / 7 + 1
            val column = position % 7
            val datedPosts = ScheduleCalendar.postsOnDate(posts, date, zoneId)
            addView(calendarDayCell(date, datedPosts), calendarCellParams(row, column, dayHeight))
        }
    }

    private fun calendarDayCell(date: LocalDate?, posts: List<ScheduledPost>): View = RoundedLinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5), dp(5), dp(5), dp(5))
        roundedCorners = 0
        background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(ContextCompat.getColor(context, R.color.schedule_calendar_cell))
        }
        bindCalendarCellGestures(this, date, posts)
        if (date == null) return@apply
        addView(TextView(this@ScheduleActivity).apply {
            text = date.dayOfMonth.toString()
            textSize = 14f
            typeface = TwidgetFonts.oneUiSans(context, if (date == LocalDate.now()) 700 else 600)
            setTextColor(ContextCompat.getColor(
                context,
                if (date == LocalDate.now()) R.color.oneui_accent else R.color.oneui_text_primary,
            ))
        })
        posts.take(2).forEach { post ->
            addView(TextView(this@ScheduleActivity).apply {
                val mediaCount = post.thread.sumOf { it.media.size }
                text = buildString {
                    append(post.thread.firstOrNull()?.text?.take(42)?.ifBlank {
                        getString(R.string.schedule_media_post)
                    })
                    if (mediaCount > 0) append("\n▣ $mediaCount")
                }
                textSize = 8f
                maxLines = 3
                setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.schedule_calendar_post))
                }
                bindCalendarCellGestures(this, date, listOf(post))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(3)
            })
        }
        if (posts.size > 2) addView(TextView(this@ScheduleActivity).apply {
            text = "+${posts.size - 2}"
            textSize = 8f
            gravity = Gravity.END
        })
    }

    private fun bindCalendarCellGestures(
        cell: View,
        date: LocalDate?,
        posts: List<ScheduledPost>,
    ) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (date == null) return false
                posts.firstOrNull()?.let { openEditor(it) } ?: openEditor(null, date)
                return true
            }

            override fun onFling(
                first: MotionEvent?,
                second: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                first ?: return false
                val dx = second.x - first.x
                if (abs(dx) < dp(56) || abs(dx) < abs(second.y - first.y)) return false
                changeCalendarMonth(if (dx < 0) 1 else -1)
                return true
            }
        })
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        cell.isClickable = true
        cell.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.x
                downY = event.y
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > touchSlop && dx > dy) {
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            val handled = detector.onTouchEvent(event)
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
            handled
        }
    }

    private fun changeCalendarMonth(delta: Int) {
        val travel = dp(28).toFloat() * if (delta > 0) -1 else 1
        content.animate().cancel()
        content.animate()
            .alpha(0f)
            .translationX(travel)
            .setDuration(120)
            .withEndAction {
                calendarMonth = calendarMonth.plusMonths(delta.toLong())
                selectedCalendarDate = null
                renderQueue()
                content.translationX = -travel
                content.animate().alpha(1f).translationX(0f).setDuration(180).start()
            }
            .start()
    }

    private fun calendarCellParams(row: Int, column: Int, height: Int): GridLayout.LayoutParams =
        GridLayout.LayoutParams(
            GridLayout.spec(row),
            GridLayout.spec(column, 1, 1f),
        ).apply {
            width = 0
            this.height = height
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun calendarEmptyState(): View = card().apply {
        gravity = Gravity.CENTER
        addView(metaText(getString(R.string.schedule_calendar_empty)).apply { gravity = Gravity.CENTER })
    }

    private fun queueCard(post: ScheduledPost): View = card().apply {
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(6), dp(4), dp(6), dp(12)) }
            val ready = post.status == ScheduleStatus.NEEDS_ACTION
            val selected = post.id in selectedQueueIds
            val cardItem = CardItemView(this@ScheduleActivity).apply {
                title = queueCardTitle(post)
                val body = post.thread.firstOrNull()?.text?.take(110)?.ifBlank {
                    getString(R.string.schedule_media_post)
                } ?: getString(R.string.schedule_empty_post)
                val mediaCount = post.thread.sumOf { it.media.size }
                val mediaSummary = if (mediaCount == 0) body else "$body\n" + if (mediaCount == 1) {
                    getString(R.string.schedule_one_image_attached)
                } else {
                    getString(R.string.schedule_images_attached, mediaCount)
                }
                summary = buildString {
                    append(
                        if (
                            post.provider == ScheduleProvider.POSTPONE &&
                            post.status == ScheduleStatus.SCHEDULED
                        ) {
                            "$mediaSummary\n${getString(R.string.schedule_postpone_auto_summary)}"
                        } else {
                            mediaSummary
                        },
                    )
                    if (viewingTrash) {
                        trashDaysLeft(post.deletedAt)?.let { days ->
                            append('\n')
                            append(getString(R.string.schedule_trash_days_left, days))
                        }
                    }
                }
                getTitleView().maxLines = 1
                getSummaryView().maxLines = 4
                bindQueueCardLeadingIcon(this, post, ready, selected)
                if (!queueSelectionMode && !viewingTrash) {
                    bindQueueCardEndActions(this, post)
                }
            }
            addView(cardItem)
            attachQueueCardInteractions(cardItem, post)
            if (ready) addView(actionRow().apply {
                setPadding(dp(16), 0, dp(16), dp(14))
                addView(actionButton(getString(R.string.schedule_post_now)) { postReady(post) })
                addView(actionButton(getString(R.string.schedule_copy_text)) {
                    showHandoff(XComposeIntents.copyText(
                        this@ScheduleActivity,
                        post.thread.firstOrNull()?.text.orEmpty(),
                    ))
                })
                if (post.thread.any { it.media.isNotEmpty() }) {
                    addView(actionButton(getString(R.string.schedule_download_media)) {
                        downloadPostMedia(post)
                    })
                }
            })
            post.errorMessage?.takeIf(String::isNotBlank)?.let {
                addView(metaText(getString(R.string.schedule_error_detail, it)).apply {
                    setTextColor(ContextCompat.getColor(context, R.color.metric_red))
                })
            }
    }

    private fun postReady(post: ScheduledPost) {
        if (post.thread.size > 1) {
            renderChecklist(post)
            return
        }
        showHandoff(XComposeIntents.openCompose(
            this,
            post.thread.firstOrNull()?.text.orEmpty(),
        ))
    }

    private fun downloadPostMedia(post: ScheduledPost) {
        val items = post.thread.filter { it.media.isNotEmpty() }
        if (items.isEmpty()) return
        setBusy(true)
        AppExecutors.execute(
            onRejected = { runOnUiThread { setBusy(false); toast(R.string.schedule_busy) } },
        ) {
            val outcomes = items.map { ScheduleMediaExporter.downloadItem(this, it) }
            val savedCount = outcomes.sumOf { it.savedCount }
            val details = outcomes.mapNotNull { it.detail }.distinct().joinToString("\n").ifBlank { null }
            val outcome = when {
                savedCount > 0 -> ScheduleMediaExportOutcome(
                    ScheduleMediaExportResult.SAVED,
                    savedCount,
                    details,
                )
                else -> ScheduleMediaExportOutcome(
                    ScheduleMediaExportResult.FAILED,
                    detail = details,
                )
            }
            runOnUiThread {
                setBusy(false)
                if (!isFinishing && !isDestroyed) showExportOutcome(outcome)
            }
        }
    }

    private fun queueCardTitle(post: ScheduledPost): String = when {
        post.status == ScheduleStatus.NEEDS_ACTION -> getString(R.string.schedule_ready_now)
        else -> queueDateTitle(post.scheduledAt)
    }

    private fun bindQueueCardLeadingIcon(
        cardItem: CardItemView,
        post: ScheduledPost,
        ready: Boolean,
        selected: Boolean,
    ) {
        when {
            queueSelectionMode -> {
                val iconRes = if (selected) {
                    OneUiIconR.drawable.ic_oui_checkbox_checked
                } else {
                    OneUiIconR.drawable.ic_oui_checkbox_unchecked
                }
                cardItem.icon = ContextCompat.getDrawable(cardItem.context, iconRes)
                cardItem.iconSize = dp(22)
                cardItem.getIconImageView().imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.oneui_accent),
                )
            }
            ready -> {
                cardItem.icon = ContextCompat.getDrawable(cardItem.context, R.drawable.schedule_ready_dot)
                cardItem.iconSize = dp(6)
                cardItem.getIconImageView().imageTintList = null
            }
            else -> {
                cardItem.icon = null
            }
        }
    }

    private fun attachQueueCardInteractions(cardItem: CardItemView, post: ScheduledPost) {
        val anchor = cardItem.findViewById<View>(OneUiDesignR.id.cardview_container) ?: return
        if (viewingTrash) {
            if (queueSelectionMode) {
                anchor.setOnClickListener { toggleQueueSelection(post.id) }
            } else {
                anchor.setOnClickListener { enterQueueSelection(post.id) }
            }
            return
        }
        if (selectedQueueView == ScheduleQueueView.CALENDAR) {
            anchor.setOnClickListener { openEditor(post) }
            return
        }
        if (queueSelectionMode) {
            anchor.setOnClickListener { toggleQueueSelection(post.id) }
            return
        }

        val handler = Handler(Looper.getMainLooper())
        val touchSlop = ViewConfiguration.get(anchor.context).scaledTouchSlop
        val menuDelay = ViewConfiguration.getLongPressTimeout().toLong()
        val bulkDelay = menuDelay * 2
        var downX = 0f
        var downY = 0f
        var menuShown = false
        var bulkTriggered = false

        val showMenu = Runnable {
            if (!bulkTriggered && !queueSelectionMode) {
                menuShown = true
                showQueueMenu(anchor, post)
            }
        }
        val showBulk = Runnable {
            if (queueSelectionMode) return@Runnable
            bulkTriggered = true
            handler.removeCallbacks(showMenu)
            dismissQueueMenu()
            enterQueueSelection(post.id)
        }

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (!menuShown && !bulkTriggered) {
                    openEditor(post)
                }
                return true
            }
        })

        anchor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    menuShown = false
                    bulkTriggered = false
                    downX = event.x
                    downY = event.y
                    handler.postDelayed(showMenu, menuDelay)
                    handler.postDelayed(showBulk, bulkDelay)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                        handler.removeCallbacks(showMenu)
                        handler.removeCallbacks(showBulk)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(showMenu)
                    handler.removeCallbacks(showBulk)
                }
            }
            gestureDetector.onTouchEvent(event)
            menuShown || bulkTriggered
        }
    }

    private fun bindQueueCardEndActions(
        cardItem: CardItemView,
        post: ScheduledPost,
    ) {
        cardItem.getEndImageView().visibility = View.GONE
        val endView = cardItem.findViewById<View>(OneUiDesignR.id.end_view) ?: return
        val parent = endView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(endView)
        if (index < 0) return
        val layoutParams = endView.layoutParams
        parent.removeView(endView)
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnTouchListener { _, _ -> true }
            if (post.status == ScheduleStatus.DRAFT) {
                addView(
                    queueQuickActionButton(
                        iconRes = if (post.pinned) {
                            OneUiIconR.drawable.ic_oui_pin
                        } else {
                            OneUiIconR.drawable.ic_oui_pin_outline
                        },
                        contentDescription = if (post.pinned) {
                            getString(R.string.schedule_unpin)
                        } else {
                            getString(R.string.schedule_pin)
                        },
                        accent = post.pinned,
                    ) { togglePin(post) },
                )
            }
            addView(
                queueQuickActionButton(
                    iconRes = OneUiIconR.drawable.ic_oui_more,
                    contentDescription = getString(R.string.schedule_more_actions),
                ) { button -> showQueueMenu(button, post) },
            )
            addView(
                queueQuickActionButton(
                    iconRes = OneUiIconR.drawable.ic_oui_edit_outline,
                    contentDescription = getString(R.string.schedule_edit),
                ) { openEditor(post) },
            )
        }
        parent.addView(actions, index, layoutParams)
    }

    private fun queueQuickActionButton(
        @DrawableRes iconRes: Int,
        contentDescription: String,
        accent: Boolean = false,
        onClick: (AppCompatImageButton) -> Unit,
    ): AppCompatImageButton = AppCompatImageButton(this).apply {
        setImageResource(iconRes)
        this.contentDescription = contentDescription
        background = TypedValue().let { value ->
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
            ContextCompat.getDrawable(context, value.resourceId)
        }
        imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                context,
                if (accent) R.color.oneui_accent else R.color.oneui_text_secondary,
            ),
        )
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener { onClick(this) }
    }

    private fun dismissQueueMenu() {
        activeQueueMenu?.dismiss()
        activeQueueMenu = null
    }

    private fun showQueueMenu(anchor: View, post: ScheduledPost) {
        if (queueSelectionMode) return
        dismissQueueMenu()
        activeQueueMenu = PopupMenu(this, anchor).apply {
            setOnDismissListener { activeQueueMenu = null }
            menu.add(getString(R.string.schedule_edit)).setOnMenuItemClickListener {
                openEditor(post)
                true
            }
            if (post.status == ScheduleStatus.DRAFT) {
                menu.add(
                    if (post.pinned) getString(R.string.schedule_unpin) else getString(R.string.schedule_pin),
                ).setOnMenuItemClickListener {
                    togglePin(post)
                    true
                }
            }
            menu.add(getString(R.string.schedule_duplicate)).setOnMenuItemClickListener {
                duplicate(post)
                true
            }
            menu.add(getString(R.string.delete)).setOnMenuItemClickListener {
                deletePost(post)
                true
            }
            show()
        }
    }

    private fun enterQueueSelection(postId: String) {
        dismissQueueMenu()
        queueSelectionMode = true
        selectedQueueIds.clear()
        selectedQueueIds += postId
        renderQueue()
    }

    private fun toggleQueueSelection(postId: String) {
        if (!selectedQueueIds.add(postId)) {
            selectedQueueIds.remove(postId)
        }
        if (selectedQueueIds.isEmpty()) {
            exitQueueSelection()
        } else {
            renderQueue()
        }
    }

    private fun exitQueueSelection() {
        if (!queueSelectionMode && selectedQueueIds.isEmpty()) return
        dismissQueueMenu()
        queueSelectionMode = false
        selectedQueueIds.clear()
        renderQueue()
    }

    private fun selectedQueuePosts(): List<ScheduledPost> =
        currentQueuePosts().filter { it.id in selectedQueueIds }

    private fun restoreSelectedTrash() {
        val posts = selectedQueuePosts()
        if (posts.isEmpty()) return
        posts.forEach { store.restoreFromTrash(it.id) }
        exitQueueSelection()
        renderQueue()
        invalidateOptionsMenu()
    }

    private fun permanentlyDeleteSelectedTrash() {
        val posts = selectedQueuePosts()
        if (posts.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.schedule_permanently_delete_title, posts.size))
            .setMessage(R.string.schedule_permanently_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                posts.forEach(::removePostLocally)
                exitQueueSelection()
                renderQueue()
                invalidateOptionsMenu()
            }
            .show()
    }

    private fun trashDaysLeft(deletedAt: Long?): Int? {
        deletedAt ?: return null
        val remaining = deletedAt + ScheduleTrashPolicy.RETENTION_MS - System.currentTimeMillis()
        if (remaining <= 0L) return 0
        return ((remaining + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(1)
    }

    private fun movePostsToTrash(posts: List<ScheduledPost>) {
        posts.forEach { post ->
            if (post.provider == ScheduleProvider.LOCAL_REMINDER) {
                LocalReminderScheduler(this).cancel(post.id)
                ScheduleNotificationHelper.cancel(this, post.id)
            }
            store.moveToTrash(post.id)
        }
    }

    private fun bulkSetPinned(selected: Boolean) {
        val posts = selectedQueuePosts().filter { it.status == ScheduleStatus.DRAFT }
        if (posts.isEmpty()) return
        val now = System.currentTimeMillis()
        posts.forEach { post ->
            store.upsert(post.copy(pinned = selected, updatedAt = now))
        }
        exitQueueSelection()
    }

    private fun bulkDeleteSelected() {
        val posts = selectedQueuePosts()
        if (posts.isEmpty()) return
        deletePosts(posts)
    }

    private fun togglePin(post: ScheduledPost) {
        val now = System.currentTimeMillis()
        store.upsert(post.copy(pinned = !post.pinned, updatedAt = now))
        renderQueue()
    }

    private fun queueDateTitle(value: Long?): String = value?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: getString(R.string.schedule_no_time)

    private fun duplicate(post: ScheduledPost) {
        val now = System.currentTimeMillis()
        val duplicate = post.copy(
            id = UUID.randomUUID().toString(),
            status = ScheduleStatus.DRAFT,
            remotePostId = null,
            errorMessage = null,
            pinned = false,
            deletedAt = null,
            createdAt = now,
            updatedAt = now,
            publishedAt = null,
            thread = post.thread.map { it.copy(id = UUID.randomUUID().toString()) },
        )
        store.create(duplicate)
        openEditor(duplicate)
    }

    private fun cancelPost(post: ScheduledPost) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_cancel_title)
            .setMessage(R.string.schedule_cancel_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cancel) { _, _ ->
                if (post.provider == ScheduleProvider.POSTPONE) {
                    runRemote {
                        coordinator.cancel(post.id)
                    }
                } else {
                    showCoordinatorResult(coordinator.cancel(post.id))
                }
            }
            .show()
    }

    private fun deletePost(post: ScheduledPost) {
        deletePosts(listOf(post))
    }

    private fun deletePosts(posts: List<ScheduledPost>) {
        if (posts.isEmpty()) return
        val drafts = posts.filter { it.status == ScheduleStatus.DRAFT }
        val permanent = posts.filterNot { it.status == ScheduleStatus.DRAFT }
        if (permanent.isEmpty()) {
            confirmMoveToTrash(drafts)
            return
        }
        val message = if (permanent.size == 1) {
            getString(R.string.schedule_delete_message)
        } else {
            getString(R.string.schedule_delete_many_message, permanent.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_delete_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deletePostsPermanently(permanent) {
                    if (drafts.isNotEmpty()) {
                        movePostsToTrash(drafts)
                        exitQueueSelection()
                        renderQueue()
                        invalidateOptionsMenu()
                    }
                }
            }
            .show()
    }

    private fun confirmMoveToTrash(posts: List<ScheduledPost>) {
        if (posts.isEmpty()) return
        val message = if (posts.size == 1) {
            getString(R.string.schedule_delete_draft_message)
        } else {
            getString(R.string.schedule_delete_draft_many_message, posts.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_delete_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                movePostsToTrash(posts)
                exitQueueSelection()
                renderQueue()
                invalidateOptionsMenu()
            }
            .show()
    }

    private fun deletePostsPermanently(
        posts: List<ScheduledPost>,
        onComplete: () -> Unit = {
            exitQueueSelection()
            renderQueue()
        },
    ) {
        if (posts.isEmpty()) {
            onComplete()
            return
        }
        val remotePosts = posts.filter {
            it.provider == ScheduleProvider.POSTPONE &&
                !it.remotePostId.isNullOrBlank() &&
                it.status != ScheduleStatus.CANCELLED
        }
        val localPosts = posts.filterNot { remotePosts.contains(it) }
        if (remotePosts.isEmpty()) {
            localPosts.forEach(::removePostLocally)
            onComplete()
        } else {
            setBusy(true)
            AppExecutors.execute(
                onRejected = {
                    runOnUiThread {
                        setBusy(false)
                        toast(R.string.schedule_busy)
                    }
                },
            ) {
                var hadError = false
                remotePosts.forEach { post ->
                    val result = coordinator.cancel(post.id)
                    when {
                        result?.isSuccess == true -> store.remove(post.id)
                        result != null -> hadError = true
                    }
                }
                runOnUiThread {
                    localPosts.forEach(::removePostLocally)
                    setBusy(false)
                    if (hadError) toast(R.string.schedule_unknown_error)
                    onComplete()
                }
            }
        }
    }

    private fun removePostLocally(post: ScheduledPost) {
        if (post.provider == ScheduleProvider.LOCAL_REMINDER) {
            LocalReminderScheduler(this).cancel(post.id)
            ScheduleNotificationHelper.cancel(this, post.id)
        }
        store.remove(post.id)
    }

    private fun openEditor(post: ScheduledPost?, date: LocalDate? = null) {
        val intent = Intent(this, ScheduleComposeActivity::class.java)
            .putExtra(ScheduleComposeActivity.EXTRA_USERNAME, requestedUsername())
            .putExtra(ScheduleComposeActivity.EXTRA_SCHEDULE_ID, post?.id)
        if (post == null && date != null) {
            val selectedTime = Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, 1)
                set(date.year, date.monthValue - 1, date.dayOfMonth)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            intent.putExtra(ScheduleComposeActivity.EXTRA_SCHEDULED_AT, selectedTime.timeInMillis)
        }
        startActivity(intent)
    }

    private fun showQueueMode() {
        queueRoot.visibility = View.VISIBLE
        switcherHost.visibility = View.VISIBLE
    }

    private fun runRemote(work: () -> ScheduleCoordinatorResult?) {
        setBusy(true)
        AppExecutors.execute(
            onRejected = {
                runOnUiThread {
                    setBusy(false)
                    toast(R.string.schedule_busy)
                }
            },
        ) {
            val result = runCatching(work)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = ::showCoordinatorResult,
                    onFailure = { showErrors(listOf(it.message ?: getString(R.string.schedule_unknown_error))) },
                )
            }
        }
    }

    private fun showCoordinatorResult(result: ScheduleCoordinatorResult?) {
        if (result == null) {
            toast(R.string.schedule_not_found)
        } else if (result.isSuccess) {
            toast(R.string.schedule_updated)
        } else {
            showErrors(result.errors)
        }
        renderQueue()
    }

    private fun renderChecklist(post: ScheduledPost) {
        showQueueMode()
        switcherHost.visibility = View.GONE
        content.removeAllViews()
        content.addView(sectionTitle(getString(R.string.schedule_publish_checklist)))
        content.addView(actionButton(getString(R.string.schedule_back_to_queue)) { renderQueue() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(18), 0, dp(18), dp(8)) }
        })
        content.addView(metaText(getString(
            R.string.schedule_checklist_summary,
            post.thread.size,
        )).apply { setPadding(dp(24), 0, dp(24), dp(16)) })

        val completed = completedItemIds(post.id)
        post.thread.forEachIndexed { index, item ->
            content.addView(checklistCard(post, index, item, item.id in completed))
        }
        primaryButton.apply {
            visibility = View.GONE
        }
        TwidgetFonts.applyTo(content)
    }

    private fun checklistCard(
        post: ScheduledPost,
        index: Int,
        item: ScheduleThreadItem,
        isDone: Boolean,
    ): View = card().apply {
        alpha = if (isDone) 0.55f else 1f
        addView(titleText(getString(R.string.schedule_thread_item_number, index + 1)))
        addView(TextView(this@ScheduleActivity).apply {
            text = item.text.ifBlank { getString(R.string.schedule_media_only) }
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
            setTextIsSelectable(true)
            setPadding(0, dp(8), 0, dp(8))
        })
        addView(metaText(mediaSummary(item.media)))
        addView(actionRow().apply {
            addView(actionButton(getString(R.string.schedule_copy_text)) {
                showHandoff(XComposeIntents.copyText(this@ScheduleActivity, item.text))
            })
            addView(actionButton(getString(R.string.schedule_open_x)) {
                showHandoff(XComposeIntents.openCompose(this@ScheduleActivity, item.text))
            })
            if (item.media.isNotEmpty()) {
                addView(actionButton(getString(R.string.schedule_download_media)) {
                    downloadMedia(item)
                })
                addView(actionButton(getString(R.string.schedule_share_media)) {
                    showHandoff(XComposeIntents.shareItem(this@ScheduleActivity, item))
                })
            }
            addView(actionButton(getString(
                if (isDone) R.string.schedule_marked_done else R.string.schedule_mark_done,
            )) {
                if (!isDone) markChecklistItemDone(post, item.id)
            }.apply { isEnabled = !isDone })
        })
    }

    private fun markChecklistItemDone(post: ScheduledPost, itemId: String) {
        val completed = ScheduleChecklistProgress.markCompleted(this, post.id, itemId)
        if (post.thread.all { it.id in completed }) {
            val published = post.copy(
                status = ScheduleStatus.PUBLISHED,
                publishedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                errorMessage = null,
            )
            store.upsert(published)
            ScheduleNotificationHelper.cancel(this, post.id)
            toast(R.string.schedule_all_published)
            renderQueue()
        } else {
            renderChecklist(post)
        }
    }

    private fun showHandoff(outcome: XHandoffOutcome) {
        val message = when (outcome.result) {
            XHandoffResult.OPENED -> outcome.limitation ?: getString(R.string.schedule_opened_x)
            XHandoffResult.COPIED -> getString(R.string.schedule_copied)
            XHandoffResult.SHARED -> outcome.limitation ?: getString(R.string.schedule_shared)
            XHandoffResult.NO_HANDLER -> getString(R.string.schedule_no_handler)
            XHandoffResult.INVALID_MEDIA -> outcome.limitation ?: getString(R.string.schedule_invalid_media)
            XHandoffResult.FAILED -> getString(R.string.schedule_handoff_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun downloadMedia(item: ScheduleThreadItem) {
        setBusy(true)
        AppExecutors.execute(
            onRejected = {
                runOnUiThread {
                    setBusy(false)
                    toast(R.string.schedule_busy)
                }
            },
        ) {
            val outcome = ScheduleMediaExporter.downloadItem(this, item)
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                showExportOutcome(outcome)
            }
        }
    }

    private fun showExportOutcome(outcome: ScheduleMediaExportOutcome) {
        val message = when (outcome.result) {
            ScheduleMediaExportResult.SAVED -> {
                if (outcome.detail.isNullOrBlank()) {
                    getString(R.string.schedule_media_saved, outcome.savedCount)
                } else {
                    getString(R.string.schedule_media_saved_partial, outcome.savedCount, outcome.detail)
                }
            }
            ScheduleMediaExportResult.NOTHING_TO_SAVE -> getString(R.string.schedule_media_nothing_to_save)
            ScheduleMediaExportResult.FAILED -> outcome.detail ?: getString(R.string.schedule_media_save_failed)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun completedItemIds(postId: String): Set<String> =
        ScheduleChecklistProgress.completed(this, postId)

    private fun setBusy(value: Boolean) {
        busy = value
        primaryButton.isEnabled = !value
    }

    private fun showErrors(errors: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_error_title)
            .setMessage(errors.filter(String::isNotBlank).joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun emptyState(trash: Boolean = false): View = card().apply {
        gravity = Gravity.CENTER
        addView(titleText(
            getString(if (trash) R.string.schedule_trash_empty else R.string.schedule_empty_title),
        ).apply {
            gravity = Gravity.CENTER
        })
        addView(metaText(
            getString(
                if (trash) R.string.schedule_trash_retention else R.string.schedule_empty_summary,
            ),
        ).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun card(): LinearLayout = RoundedLinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.oneui_card_bg))
        roundedCornersColor = ContextCompat.getColor(context, R.color.oneui_bg)
        setPadding(dp(20), dp(18), dp(20), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(dp(6), dp(6), dp(6), dp(10))
        }
    }

    private fun actionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        setPadding(0, dp(10), 0, 0)
    }

    private fun actionButton(
        label: String,
        selected: Boolean = false,
        action: () -> Unit,
    ): AppCompatButton = AppCompatButton(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = dp(42)
        minWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        contentDescription = label
        if (selected) {
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.oneui_accent_translucent),
            )
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(6) }
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 19f
        typeface = TwidgetFonts.oneUiSans(context, 700)
        setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        setPadding(dp(24), dp(14), dp(24), dp(8))
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        typeface = TwidgetFonts.oneUiSans(context, 600)
        setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        maxLines = 4
    }

    private fun metaText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(ContextCompat.getColor(context, R.color.oneui_text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun mediaSummary(media: List<ScheduleMediaSource>): String {
        if (media.isEmpty()) return getString(R.string.schedule_no_media)
        val names = media.map {
            when (it) {
                is LocalUriMedia -> it.displayName ?: getString(R.string.schedule_local_media)
                is PublicUrlMedia -> it.url
                is PostponeLibraryMedia -> it.name
            }
        }
        return getString(R.string.schedule_media_summary, media.size, names.joinToString(", "))
    }

    private fun requestedUsername(): String =
        intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')

    private fun queueAccountLabel(): String =
        requestedUsername().takeIf(String::isNotBlank)?.let { "@$it" }
            ?: getString(R.string.schedule_all_accounts)

    private fun providerLabel(provider: ScheduleProvider): String = getString(
        if (provider == ScheduleProvider.POSTPONE) {
            R.string.schedule_provider_postpone
        } else {
            R.string.schedule_provider_local
        },
    )

    private fun statusLabel(status: ScheduleStatus): Int = when (status) {
        ScheduleStatus.DRAFT -> R.string.schedule_status_draft
        ScheduleStatus.SCHEDULED -> R.string.schedule_status_scheduled
        ScheduleStatus.NEEDS_ACTION -> R.string.schedule_status_needs_action
        ScheduleStatus.PUBLISHED -> R.string.schedule_status_published
        ScheduleStatus.FAILED -> R.string.schedule_status_failed
        ScheduleStatus.CANCELLED -> R.string.schedule_status_cancelled
    }

    private fun formattedTime(value: Long?): String =
        value?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            ?: getString(R.string.schedule_no_time)

    private fun toast(message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun <T> MutableList<T>.swap(first: Int, second: Int) {
        val value = this[first]
        this[first] = this[second]
        this[second] = value
    }

    private enum class ScheduleQueueView {
        LIST,
        CALENDAR,
    }

    companion object {
        const val EXTRA_USERNAME = "com.tjg.twidget.extra.SCHEDULE_USERNAME"
        const val EXTRA_OPEN_TRASH = "com.tjg.twidget.extra.SCHEDULE_OPEN_TRASH"
    }
}
