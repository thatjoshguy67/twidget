package com.tjg.twidget.schedule

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.OneUiSpinner
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startLeftSidePopOverActivity
import com.tjg.twidget.ui.startRightSidePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR
import dev.oneuiproject.oneui.design.R as OneUiDesignR
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.widget.RoundedLinearLayout
import dev.oneuiproject.oneui.widget.RoundedNestedScrollView
import dev.oneuiproject.oneui.widget.RoundedTabLayout
import dev.oneuiproject.oneui.widget.ScrollAwareFloatingActionButton
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

abstract class ScheduleQueueHostActivity : FoldablePopOverActivity() {
    protected open val embedsScheduleQueue: Boolean = false

    private lateinit var scheduleToolbar: ToolbarLayout
    private lateinit var content: LinearLayout
    private lateinit var primaryButton: ScrollAwareFloatingActionButton
    private lateinit var queueRoot: View
    private lateinit var queueTabs: RoundedTabLayout
    private lateinit var scroll: RoundedNestedScrollView
    private lateinit var refresh: SwipeRefreshLayout
    private lateinit var selectionBottomNav: BottomNavigationView
    private lateinit var trashBottomNav: BottomNavigationView
    private val store by lazy { ScheduleStore(this) }
    private val coordinator by lazy { ScheduleCoordinator(this) }

    private var selectedQueueStatus: ScheduleStatus? = null
    private var selectedQueueView = ScheduleQueueView.LIST
    private var calendarMonth = YearMonth.now()
    private var selectedCalendarDate: LocalDate? = null
    private var busy = false
    private var syncing = false
    private var navigationBarInset = 0
    private var queueSelectionMode = false
    private val selectedQueueIds = linkedSetOf<String>()
    private var activeQueueMenu: PopupMenu? = null
    private var viewingTrash = false
    private val bottomNavigationAnchor = ViewTreeObserver.OnPreDrawListener {
        if (::selectionBottomNav.isInitialized) {
            anchorBottomNavigation(selectionBottomNav)
            anchorBottomNavigation(trashBottomNav)
        }
        true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (embedsScheduleQueue) return
        setContentView(R.layout.activity_schedule)
        attachScheduleQueue(
            toolbar = findViewById(R.id.schedule_root),
            root = findViewById(R.id.schedule_queue_container),
            initiallyVisible = true,
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    queueSelectionMode -> exitQueueSelection()
                    viewingTrash && intent.getBooleanExtra(EXTRA_OPEN_TRASH, false) -> finish()
                    viewingTrash -> exitTrashView()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
        applyEdgeToEdgeInsets(findViewById(R.id.schedule_root), ::updateScheduleBottomInsets)

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
            else -> {
                renderQueue()
                syncPostponeQueue(userInitiated = false)
            }
        }
    }

    protected fun attachEmbeddedScheduleQueue(toolbar: ToolbarLayout, root: View) {
        check(embedsScheduleQueue) { "Only embedded queue hosts may attach an embedded schedule page" }
        attachScheduleQueue(toolbar, root, initiallyVisible = false)
    }

    private fun attachScheduleQueue(
        toolbar: ToolbarLayout,
        root: View,
        initiallyVisible: Boolean,
    ) {
        scheduleToolbar = toolbar
        queueRoot = root
        content = findViewById(R.id.schedule_content)
        primaryButton = findViewById(R.id.schedule_primary_button)
        queueTabs = findViewById(R.id.schedule_tabs)
        scroll = findViewById(R.id.schedule_scroll)
        refresh = findViewById<SwipeRefreshLayout>(R.id.schedule_refresh).apply {
            OneUiSpinner.attachToSwipeRefresh(this)
            setOnRefreshListener { syncPostponeQueue(userInitiated = true) }
        }
        selectionBottomNav = findViewById(R.id.schedule_selection_bottom_nav)
        trashBottomNav = findViewById(R.id.schedule_trash_bottom_nav)
        queueRoot.viewTreeObserver.addOnPreDrawListener(bottomNavigationAnchor)
        setupBottomNavigation()
        setupQueueViewSwitcher()
        queueRoot.visibility = if (initiallyVisible) View.VISIBLE else View.GONE
    }

    protected fun updateScheduleBottomInsets(inset: Int) {
        if (!::primaryButton.isInitialized) return
        navigationBarInset = inset
        primaryButton.updateBottomMarginForNavigationBar(scheduleDp(20), inset)
        selectionBottomNav.updateBottomMarginForNavigationBar(0, inset)
        trashBottomNav.updateBottomMarginForNavigationBar(0, inset)
    }

    private fun anchorBottomNavigation(navigation: BottomNavigationView) {
        if (navigation.visibility != View.VISIBLE || navigation.height == 0) {
            navigation.translationY = 0f
            return
        }
        val windowRoot = findViewById<View>(android.R.id.content) ?: return
        val rootLocation = IntArray(2)
        val navigationLocation = IntArray(2)
        windowRoot.getLocationOnScreen(rootLocation)
        navigation.getLocationOnScreen(navigationLocation)
        val targetBottom = rootLocation[1] + windowRoot.height - navigationBarInset
        val currentBottom = navigationLocation[1] + navigation.height
        val adjustment = targetBottom - currentBottom
        if (kotlin.math.abs(adjustment) >= 1) {
            navigation.translationY += adjustment.toFloat()
        }
    }

    override fun onDestroy() {
        if (::queueRoot.isInitialized && queueRoot.viewTreeObserver.isAlive) {
            queueRoot.viewTreeObserver.removeOnPreDrawListener(bottomNavigationAnchor)
        }
        super.onDestroy()
    }

    protected fun showEmbeddedScheduleQueue() {
        check(embedsScheduleQueue && ::queueRoot.isInitialized)
        viewingTrash = false
        queueRoot.visibility = View.VISIBLE
        renderQueue()
        syncPostponeQueue(userInitiated = false)
    }

    protected fun hideEmbeddedScheduleQueue() {
        if (!::queueRoot.isInitialized) return
        if (queueSelectionMode) exitQueueSelection()
        queueRoot.visibility = View.GONE
    }

    protected fun refreshEmbeddedScheduleQueue() {
        if (::queueRoot.isInitialized && queueRoot.visibility == View.VISIBLE) renderQueue()
    }

    protected fun handleEmbeddedScheduleBack(): Boolean {
        if (!queueSelectionMode) return false
        exitQueueSelection()
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onRestart() {
        super.onRestart()
        if (::queueRoot.isInitialized && queueRoot.visibility == View.VISIBLE) {
            renderQueue()
            syncPostponeQueue(userInitiated = false)
        }
    }

    override fun allowsPopOverPresentation(): Boolean =
        intent.getBooleanExtra(EXTRA_OPEN_TRASH, false)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.schedule, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.schedule_trash_menu)?.isVisible = !queueSelectionMode && !viewingTrash
        val filterGroup = menu.findItem(R.id.schedule_filter_all_menu)?.groupId ?: 0
        for (index in 0 until menu.size()) {
            val item = menu.getItem(index)
            if (item.groupId == filterGroup) {
                item.isVisible = !viewingTrash && !queueSelectionMode
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
            openTrashPopover()
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

    private fun setupBottomNavigation() {
        selectionBottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.schedule_selection_pin -> {
                    val posts = selectedPinnablePosts()
                    if (posts.isNotEmpty()) bulkSetPinned(selected = !posts.all { it.pinned })
                }
                R.id.schedule_selection_duplicate -> duplicateSelectedPosts()
                R.id.schedule_selection_delete -> bulkDeleteSelected()
                R.id.schedule_selection_more -> {
                    val anchor = selectionBottomNav.findViewById<View>(item.itemId) ?: selectionBottomNav
                    showQueueSelectionOverflow(anchor)
                }
                else -> return@setOnItemSelectedListener false
            }
            false
        }
        trashBottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.schedule_trash_restore -> restoreSelectedTrash()
                R.id.schedule_trash_delete -> permanentlyDeleteSelectedTrash()
                else -> return@setOnItemSelectedListener false
            }
            false
        }
    }

    private fun enterTrashView() {
        exitQueueSelection()
        viewingTrash = true
        selectedQueueView = ScheduleQueueView.LIST
        scheduleToolbar.setTitle(getString(R.string.schedule_trash))
        invalidateOptionsMenu()
        renderQueue()
    }

    private fun exitTrashView() {
        if (!viewingTrash) return
        exitQueueSelection()
        viewingTrash = false
        scheduleToolbar.setTitle(getString(R.string.schedule_title))
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
    private fun renderQueue() {
        showQueueMode()
        content.removeAllViews()
        val posts = currentQueuePosts()
        val calendarMode = !viewingTrash && selectedQueueView == ScheduleQueueView.CALENDAR
        refresh.isEnabled = !viewingTrash && !calendarMode && !queueSelectionMode
        queueTabs.visibility = if (viewingTrash) View.GONE else View.VISIBLE
        scroll.setPadding(
            scroll.paddingLeft,
            scroll.paddingTop,
            scroll.paddingRight,
            if (calendarMode) 0 else if (viewingTrash) scheduleDp(88) else scheduleDp(104),
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
            if (calendarMode) 0 else scheduleDp(24),
        )
        if (calendarMode) scroll.scrollTo(0, 0)
        if (calendarMode) {
            renderCalendar(posts)
        } else if (posts.isEmpty()) {
            content.addView(emptyState(viewingTrash))
        } else {
            if (viewingTrash) {
                content.addView(metaText(getString(R.string.schedule_trash_retention)).apply {
                    setPadding(scheduleDp(12), scheduleDp(4), scheduleDp(12), scheduleDp(8))
                })
            }
            posts.forEach { content.addView(queueCard(it)) }
        }
        updateQueueSelectionUi(calendarMode)
        TwidgetFonts.applyTo(content)
    }

    private fun syncPostponeQueue(userInitiated: Boolean) {
        if (syncing || viewingTrash || !::refresh.isInitialized) {
            if (::refresh.isInitialized && !syncing) refresh.isRefreshing = false
            return
        }
        if (ScheduleSettingsStore.defaultProvider(this) != ScheduleProvider.POSTPONE) {
            refresh.isRefreshing = false
            if (userInitiated) renderQueue()
            return
        }
        syncing = true
        if (userInitiated) refresh.isRefreshing = true
        AppExecutors.execute(
            onRejected = {
                runOnUiThread {
                    syncing = false
                    refresh.isRefreshing = false
                    if (userInitiated) toast(R.string.schedule_busy)
                }
            },
        ) {
            val result = PostponeScheduleSync(this).sync()
            runOnUiThread {
                syncing = false
                refresh.isRefreshing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderQueue()
                if (userInitiated) {
                    if (result.isSuccess) {
                        Toast.makeText(
                            this,
                            getString(R.string.schedule_sync_complete, result.imported, result.updated),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            this,
                            getString(
                                R.string.schedule_sync_failed,
                                result.errors.firstOrNull() ?: getString(R.string.schedule_unknown_error),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateQueueSelectionUi(calendarMode: Boolean) {
        val showTrashBar = viewingTrash && !calendarMode
        val showSelection = !viewingTrash && queueSelectionMode && !calendarMode
        val toolbar = scheduleToolbar
        if (!toolbar.isActionMode) toolbar.setTitle(
            if (viewingTrash) {
                getString(R.string.schedule_trash)
            } else {
                getString(R.string.schedule_title)
            },
        )
        val pinnable = selectedPinnablePosts()
        val unpin = pinnable.isNotEmpty() && pinnable.all { it.pinned }
        selectionBottomNav.menu.findItem(R.id.schedule_selection_pin)?.apply {
            isEnabled = pinnable.isNotEmpty()
            title = getString(if (unpin) R.string.schedule_unpin else R.string.schedule_pin)
            setIcon(
                if (unpin) OneUiIconR.drawable.ic_oui_pin_off else OneUiIconR.drawable.ic_oui_pin_outline,
            )
        }
        val hasSelection = selectedQueueIds.isNotEmpty()
        selectionBottomNav.menu.findItem(R.id.schedule_selection_duplicate)?.isEnabled = hasSelection
        selectionBottomNav.menu.findItem(R.id.schedule_selection_delete)?.isEnabled = hasSelection
        selectionBottomNav.menu.findItem(R.id.schedule_selection_more)?.apply {
            isEnabled = selectedQueueIds.size == 1
            isVisible = selectedQueueIds.size == 1
        }
        if (showSelection) {
            val total = currentQueuePosts().size
            toolbar.updateAllSelector(
                selectedQueueIds.size,
                enabled = total > 0,
                checked = total > 0 && selectedQueueIds.size == total,
            )
        }
        selectionBottomNav.visibility = if (showSelection) View.VISIBLE else View.GONE
        trashBottomNav.visibility = if (showTrashBar) View.VISIBLE else View.GONE
        val allTrashSelected = selectedQueueIds.isNotEmpty() &&
            selectedQueueIds.size == currentQueuePosts().size
        trashBottomNav.menu.findItem(R.id.schedule_trash_restore)?.apply {
            isEnabled = selectedQueueIds.isNotEmpty()
            title = getString(if (allTrashSelected) R.string.schedule_restore_all else R.string.schedule_restore)
        }
        trashBottomNav.menu.findItem(R.id.schedule_trash_delete)?.apply {
            isEnabled = selectedQueueIds.isNotEmpty()
            title = getString(if (allTrashSelected) R.string.schedule_delete_all else R.string.delete)
        }
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
        queueTabs.removeAllTabs()
        queueTabs.addTab(
            queueTabs.newTab()
                .setText(R.string.schedule_view_list)
                .setContentDescription(R.string.schedule_view_list),
            true,
        )
        queueTabs.addTab(
            queueTabs.newTab()
                .setText(R.string.schedule_view_calendar)
                .setContentDescription(R.string.schedule_view_calendar),
        )
        queueTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val next = if (tab.position == 0) ScheduleQueueView.LIST else ScheduleQueueView.CALENDAR
                if (next == selectedQueueView) return
                exitQueueSelection()
                selectedQueueView = next
                selectedCalendarDate = null
                animateQueueModeChange(if (tab.position == 0) -1 else 1)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun animateQueueModeChange(direction: Int) {
        val travel = scheduleDp(18).toFloat() * direction
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
        setPadding(scheduleDp(10), scheduleDp(8), scheduleDp(10), scheduleDp(8))
        addView(actionButton("‹") {
            changeCalendarMonth(-1)
        }.apply { contentDescription = getString(R.string.schedule_previous_month) })
        addView(TextView(this@ScheduleQueueHostActivity).apply {
            text = calendarMonth.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())).uppercase(Locale.getDefault())
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = TwidgetFonts.oneUiSans(context, 700)
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        }, LinearLayout.LayoutParams(0, scheduleDp(48), 1f))
        addView(actionButton("›") {
            changeCalendarMonth(1)
        }.apply { contentDescription = getString(R.string.schedule_next_month) })
    }

    private fun calendarGrid(posts: List<ScheduledPost>, zoneId: ZoneId): View = GridLayout(this).apply {
        columnCount = 7
        setPadding(scheduleDp(8), scheduleDp(4), scheduleDp(8), scheduleDp(12))
        val firstDay = calendarMonth.atDay(1)
        val leadingDays = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        val rowCount = (leadingDays + calendarMonth.lengthOfMonth() + 6) / 7
        val viewportHeight = scroll.height.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - scheduleDp(220))
        val gridPadding = scheduleDp(16)
        val weekHeaderHeight = scheduleDp(28)
        val rowMargins = scheduleDp(4) * (rowCount + 1)
        val dayHeight = ((
            viewportHeight - scheduleDp(58) - content.paddingTop - content.paddingBottom -
                gridPadding - weekHeaderHeight - rowMargins
            ) / rowCount).coerceAtLeast(scheduleDp(44))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            gridPadding + weekHeaderHeight + rowMargins + dayHeight * rowCount,
        )
        DayOfWeek.entries.forEachIndexed { column, day ->
            addView(TextView(this@ScheduleQueueHostActivity).apply {
                text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = TwidgetFonts.oneUiSans(context, 700)
                setTextColor(ContextCompat.getColor(context, R.color.oneui_text_secondary))
            }, calendarCellParams(0, column, scheduleDp(28)))
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
        setPadding(scheduleDp(5), scheduleDp(5), scheduleDp(5), scheduleDp(5))
        roundedCorners = 0
        background = GradientDrawable().apply {
            cornerRadius = scheduleDp(5).toFloat()
            setColor(ContextCompat.getColor(context, R.color.schedule_calendar_cell))
        }
        bindCalendarCellGestures(this, date, posts)
        if (date == null) return@apply
        addView(TextView(this@ScheduleQueueHostActivity).apply {
            text = date.dayOfMonth.toString()
            textSize = 14f
            typeface = TwidgetFonts.oneUiSans(context, if (date == LocalDate.now()) 700 else 600)
            setTextColor(ContextCompat.getColor(
                context,
                if (date == LocalDate.now()) R.color.oneui_accent else R.color.oneui_text_primary,
            ))
        })
        posts.take(2).forEach { post ->
            addView(TextView(this@ScheduleQueueHostActivity).apply {
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
                setPadding(scheduleDp(4), scheduleDp(4), scheduleDp(4), scheduleDp(4))
                background = GradientDrawable().apply {
                    cornerRadius = scheduleDp(3).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.schedule_calendar_post))
                }
                bindCalendarCellGestures(this, date, listOf(post))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = scheduleDp(3)
            })
        }
        if (posts.size > 2) addView(TextView(this@ScheduleQueueHostActivity).apply {
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
                if (abs(dx) < scheduleDp(56) || abs(dx) < abs(second.y - first.y)) return false
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
        val travel = scheduleDp(28).toFloat() * if (delta > 0) -1 else 1
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
            setMargins(scheduleDp(2), scheduleDp(2), scheduleDp(2), scheduleDp(2))
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
            ).apply { setMargins(scheduleDp(6), scheduleDp(4), scheduleDp(6), scheduleDp(12)) }
            val ready = post.status == ScheduleStatus.NEEDS_ACTION
            val selected = post.id in selectedQueueIds
            val cardItem = CardItemView(this@ScheduleQueueHostActivity).apply {
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
                getTitleView().apply {
                    maxLines = 1
                    typeface = TwidgetFonts.oneUiSans(
                        context,
                        if (ready) 700 else 400,
                    )
                }
                getSummaryView().maxLines = 2
                bindQueueCardLeadingIcon(this, post, ready, selected)
                if (!viewingTrash) {
                    bindQueueCardEndActions(this, post)
                }
            }
            addView(cardItem)
            attachQueueCardInteractions(cardItem, post)
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
        val text = post.thread.firstOrNull()?.text.orEmpty()
        val needsCopy = !clipboardContains(text)
        val copyOutcome = if (needsCopy) XComposeIntents.copyText(this, text) else null
        val openOutcome = XComposeIntents.openCompose(this, text)
        if (openOutcome.result != XHandoffResult.OPENED) {
            showHandoff(openOutcome)
        } else if (copyOutcome?.result == XHandoffResult.COPIED) {
            Toast.makeText(this, R.string.schedule_paste_in_x, Toast.LENGTH_LONG).show()
        } else if (copyOutcome != null) {
            showHandoff(copyOutcome)
        } else {
            showHandoff(openOutcome)
        }
    }

    private fun clipboardContains(text: String): Boolean {
        if (text.isBlank()) return true
        return runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java)
            if (!clipboard.hasPrimaryClip()) return@runCatching false
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString() == text
        }.getOrDefault(false)
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

    private fun queueCardTitle(post: ScheduledPost): CharSequence = when {
        post.status == ScheduleStatus.NEEDS_ACTION -> SpannableStringBuilder(
            getString(R.string.schedule_ready_now),
        ).apply {
            append("  •")
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@ScheduleQueueHostActivity, R.color.notice_badge_orange)),
                length - 1,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(RelativeSizeSpan(0.7f), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(SuperscriptSpan(), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
                cardItem.iconSize = scheduleDp(22)
                cardItem.getIconImageView().imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.oneui_accent),
                )
            }
            ready -> cardItem.icon = null
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
            anchor.setOnLongClickListener {
                if (post.id !in selectedQueueIds) {
                    selectedQueueIds += post.id
                    invalidateOptionsMenu()
                    renderQueue()
                } else {
                    showQueueSelectionMenu(anchor)
                }
                true
            }
            return
        }

        anchor.setOnClickListener { openEditor(post) }
        anchor.setOnLongClickListener {
            enterQueueSelection(post.id)
            true
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
            if (post.status == ScheduleStatus.NEEDS_ACTION) {
                if (post.thread.any { it.media.isNotEmpty() }) {
                    addView(
                        queueQuickActionButton(
                            iconRes = OneUiIconR.drawable.ic_oui_download,
                            contentDescription = getString(R.string.schedule_download_media),
                        ) { downloadPostMedia(post) },
                    )
                }
                addView(
                    queueQuickActionButton(
                        iconRes = OneUiIconR.drawable.ic_oui_copy_outline,
                        contentDescription = getString(R.string.schedule_copy_text),
                    ) {
                        showHandoff(
                            XComposeIntents.copyText(
                                this@ScheduleQueueHostActivity,
                                post.thread.firstOrNull()?.text.orEmpty(),
                            ),
                        )
                    },
                )
                addView(
                    queueQuickActionButton(
                        iconRes = OneUiIconR.drawable.ic_oui_send,
                        contentDescription = getString(R.string.schedule_post_now),
                    ) { postReady(post) },
                )
            } else if (queueSelectionMode) {
                addView(
                    queueQuickActionButton(
                        iconRes = OneUiIconR.drawable.ic_oui_edit_outline,
                        contentDescription = getString(R.string.schedule_edit),
                    ) {
                        exitQueueSelection()
                        openEditor(post)
                    },
                )
            } else if (ScheduleQueuePolicy.canPin(post.status)) {
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
        setPadding(scheduleDp(10), scheduleDp(10), scheduleDp(10), scheduleDp(10))
        layoutParams = LinearLayout.LayoutParams(scheduleDp(40), scheduleDp(40))
        setOnClickListener { onClick(this) }
    }

    private fun dismissQueueMenu() {
        activeQueueMenu?.dismiss()
        activeQueueMenu = null
    }

    private fun showQueueSelectionMenu(anchor: View) {
        val posts = selectedQueuePosts()
        if (!queueSelectionMode || posts.isEmpty()) return
        dismissQueueMenu()
        activeQueueMenu = PopupMenu(this, anchor).apply {
            setOnDismissListener { activeQueueMenu = null }
            if (posts.size == 1) {
                menu.add(getString(R.string.schedule_edit)).setOnMenuItemClickListener {
                    exitQueueSelection()
                    openEditor(posts.single())
                    true
                }
            }
            val pinnable = posts.filter { ScheduleQueuePolicy.canPin(it.status) }
            menu.add(
                if (pinnable.isNotEmpty() && pinnable.all { it.pinned }) {
                    getString(R.string.schedule_unpin)
                } else {
                    getString(R.string.schedule_pin)
                },
            ).apply {
                isEnabled = pinnable.isNotEmpty()
                setOnMenuItemClickListener {
                    bulkSetPinned(selected = !pinnable.all { it.pinned })
                    true
                }
            }
            menu.add(getString(R.string.delete)).setOnMenuItemClickListener {
                bulkDeleteSelected()
                true
            }
            menu.add(getString(R.string.schedule_duplicate)).setOnMenuItemClickListener {
                duplicateSelectedPosts()
                true
            }
            show()
        }
    }

    private fun showQueueSelectionOverflow(anchor: View) {
        val post = selectedQueuePosts().singleOrNull() ?: return
        dismissQueueMenu()
        activeQueueMenu = PopupMenu(this, anchor).apply {
            setOnDismissListener { activeQueueMenu = null }
            menu.add(getString(R.string.schedule_edit)).setOnMenuItemClickListener {
                exitQueueSelection()
                openEditor(post)
                true
            }
            show()
        }
    }

    private fun startQueueSelectionActionMode() {
        if (viewingTrash) return
        val toolbar = scheduleToolbar
        if (toolbar.isActionMode) return
        toolbar.startActionMode(
            listener = object : ToolbarLayout.ActionModeListener {
                override fun onInflateActionMenu(menu: Menu, menuInflater: android.view.MenuInflater) {
                    // Batch actions are intentionally kept in the explicit bottom navigation.
                    // The action mode owns only Select all, the count, and Cancel.
                }

                override fun onEndActionMode() {
                    queueSelectionMode = false
                    selectedQueueIds.clear()
                    invalidateOptionsMenu()
                    renderQueue()
                }

                override fun onMenuItemClicked(item: MenuItem): Boolean = false

                override fun onSelectAll(isChecked: Boolean) {
                    selectedQueueIds.clear()
                    if (isChecked) selectedQueueIds += currentQueuePosts().map(ScheduledPost::id)
                    renderQueue()
                }

                override fun getActionModeMenuBehavior(): ToolbarLayout.ActionModeMenuBehavior =
                    ToolbarLayout.ActionModeMenuBehavior.TOOLBAR
            },
            showCancel = true,
            maxActionItems = 4,
        )
    }

    private fun enterQueueSelection(postId: String) {
        dismissQueueMenu()
        queueSelectionMode = true
        selectedQueueIds.clear()
        selectedQueueIds += postId
        startQueueSelectionActionMode()
        invalidateOptionsMenu()
        renderQueue()
    }

    private fun toggleQueueSelection(postId: String) {
        if (!selectedQueueIds.add(postId)) {
            selectedQueueIds.remove(postId)
        }
        if (selectedQueueIds.isEmpty()) {
            exitQueueSelection()
        } else {
            invalidateOptionsMenu()
            renderQueue()
        }
    }

    private fun exitQueueSelection() {
        if (!queueSelectionMode && selectedQueueIds.isEmpty()) return
        dismissQueueMenu()
        val toolbar = scheduleToolbar
        if (toolbar.isActionMode) {
            toolbar.endActionMode()
            return
        }
        queueSelectionMode = false
        selectedQueueIds.clear()
        invalidateOptionsMenu()
        renderQueue()
    }

    private fun selectedQueuePosts(): List<ScheduledPost> =
        currentQueuePosts().filter { it.id in selectedQueueIds }

    private fun selectedPinnablePosts(): List<ScheduledPost> =
        selectedQueuePosts().filter { ScheduleQueuePolicy.canPin(it.status) }

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
        val posts = selectedPinnablePosts()
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
        val locale = Locale.getDefault()
        val date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
        val month = date.format(DateTimeFormatter.ofPattern("MMM", locale))
        val time = date.format(DateTimeFormatter.ofPattern("h:mma", locale))
            .replace(" ", "")
            .lowercase(locale)
        "$month ${date.dayOfMonth}${ordinalSuffix(date.dayOfMonth)}, ${date.year} - $time"
    } ?: getString(R.string.schedule_no_time)

    private fun ordinalSuffix(day: Int): String = when {
        day % 100 in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun duplicate(post: ScheduledPost) {
        val duplicate = createDuplicate(post)
        openEditor(duplicate)
    }

    private fun duplicateSelectedPosts() {
        val posts = selectedQueuePosts()
        if (posts.isEmpty()) return
        posts.forEach(::createDuplicate)
        exitQueueSelection()
    }

    private fun createDuplicate(post: ScheduledPost): ScheduledPost {
        val now = System.currentTimeMillis()
        val duplicate = post.copy(
            id = UUID.randomUUID().toString(),
            status = ScheduleStatus.DRAFT,
            remotePostId = null,
            remoteSubmissionId = null,
            errorMessage = null,
            pinned = false,
            deletedAt = null,
            createdAt = now,
            updatedAt = now,
            publishedAt = null,
            thread = post.thread.map { it.copy(id = UUID.randomUUID().toString()) },
        )
        store.create(duplicate)
        return duplicate
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
        val trashable = posts.filter { ScheduleTrashPolicy.canMoveToTrash(it.status) }
        val permanent = posts.filterNot { ScheduleTrashPolicy.canMoveToTrash(it.status) }
        if (permanent.isEmpty()) {
            confirmMoveToTrash(trashable)
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
                    if (trashable.isNotEmpty()) {
                        movePostsToTrash(trashable)
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
        PostponePublishCheckWorker.cancel(this, post.id)
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
        startRightSidePopOverActivity(intent)
    }

    private fun showQueueMode() {
        queueRoot.visibility = View.VISIBLE
        queueTabs.visibility = if (viewingTrash) View.GONE else View.VISIBLE
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
            toast(
                if (result.fellBackToLocal) R.string.schedule_fell_back_local
                else R.string.schedule_updated,
            )
        } else {
            showErrors(result.errors)
        }
        renderQueue()
    }

    private fun renderChecklist(post: ScheduledPost) {
        showQueueMode()
        queueTabs.visibility = View.GONE
        content.removeAllViews()
        content.addView(sectionTitle(getString(R.string.schedule_publish_checklist)))
        content.addView(actionButton(getString(R.string.schedule_back_to_queue)) { renderQueue() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(scheduleDp(18), 0, scheduleDp(18), scheduleDp(8)) }
        })
        content.addView(metaText(getString(
            R.string.schedule_checklist_summary,
            post.thread.size,
        )).apply { setPadding(scheduleDp(24), 0, scheduleDp(24), scheduleDp(16)) })

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
        addView(TextView(this@ScheduleQueueHostActivity).apply {
            text = item.text.ifBlank { getString(R.string.schedule_media_only) }
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
            setTextIsSelectable(true)
            setPadding(0, scheduleDp(8), 0, scheduleDp(8))
        })
        addView(metaText(mediaSummary(item.media)))
        addView(actionRow().apply {
            addView(actionButton(getString(R.string.schedule_copy_text)) {
                showHandoff(XComposeIntents.copyText(this@ScheduleQueueHostActivity, item.text))
            })
            addView(actionButton(getString(R.string.schedule_open_x)) {
                showHandoff(XComposeIntents.openCompose(this@ScheduleQueueHostActivity, item.text))
            })
            if (item.media.isNotEmpty()) {
                addView(actionButton(getString(R.string.schedule_download_media)) {
                    downloadMedia(item)
                })
                addView(actionButton(getString(R.string.schedule_share_media)) {
                    showHandoff(XComposeIntents.shareItem(this@ScheduleQueueHostActivity, item))
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
        setPadding(scheduleDp(20), scheduleDp(18), scheduleDp(20), scheduleDp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(scheduleDp(6), scheduleDp(6), scheduleDp(6), scheduleDp(10))
        }
    }

    private fun actionRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.START
        setPadding(0, scheduleDp(10), 0, 0)
    }

    private fun actionButton(
        label: String,
        selected: Boolean = false,
        action: () -> Unit,
    ): AppCompatButton = AppCompatButton(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = scheduleDp(42)
        minWidth = 0
        setPadding(scheduleDp(14), 0, scheduleDp(14), 0)
        contentDescription = label
        if (selected) {
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.oneui_accent_translucent),
            )
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = scheduleDp(6) }
        setOnClickListener { action() }
    }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 19f
        typeface = TwidgetFonts.oneUiSans(context, 700)
        setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        setPadding(scheduleDp(24), scheduleDp(14), scheduleDp(24), scheduleDp(8))
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
        setPadding(0, scheduleDp(6), 0, 0)
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
        TwidgetStore.settings(this).username.trim().trimStart('@')

    internal fun openTrashPopover() {
        if (viewingTrash) return
        startLeftSidePopOverActivity(
            Intent(this, ScheduleActivity::class.java)
                .putExtra(EXTRA_OPEN_TRASH, true),
        )
    }

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

    internal fun scheduleDp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
