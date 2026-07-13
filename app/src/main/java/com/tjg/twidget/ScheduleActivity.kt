package com.tjg.twidget

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.widget.FloatingActionBar
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.widget.RoundedLinearLayout
import dev.oneuiproject.oneui.widget.ScrollAwareFloatingActionButton
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import dev.oneuiproject.oneui.R as OneUiIconR

class ScheduleActivity : FoldablePopOverActivity() {
    private lateinit var content: LinearLayout
    private lateinit var primaryButton: ScrollAwareFloatingActionButton
    private lateinit var queueRoot: View
    private val store by lazy { ScheduleStore(this) }
    private val coordinator by lazy { ScheduleCoordinator(this) }

    private var selectedQueueStatus: ScheduleStatus? = null
    private var selectedQueueView = ScheduleQueueView.LIST
    private var calendarMonth = YearMonth.now()
    private var selectedCalendarDate: LocalDate? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)
        content = findViewById(R.id.schedule_content)
        primaryButton = findViewById(R.id.schedule_primary_button)
        queueRoot = findViewById(R.id.schedule_root)
        findViewById<ToolbarLayout>(R.id.schedule_root)
            .setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(findViewById(R.id.schedule_root)) { inset ->
            primaryButton.updateBottomMargin(dp(20) + inset)
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
            post != null -> openEditor(post)
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
        val checked = when (selectedQueueStatus) {
            ScheduleStatus.SCHEDULED -> R.id.schedule_filter_scheduled_menu
            ScheduleStatus.DRAFT -> R.id.schedule_filter_drafts_menu
            ScheduleStatus.NEEDS_ACTION -> R.id.schedule_filter_action_menu
            ScheduleStatus.FAILED -> R.id.schedule_filter_failed_menu
            else -> R.id.schedule_filter_all_menu
        }
        menu.findItem(checked)?.isChecked = true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        selectedQueueStatus = when (item.itemId) {
            R.id.schedule_filter_all_menu -> null
            R.id.schedule_filter_scheduled_menu -> ScheduleStatus.SCHEDULED
            R.id.schedule_filter_drafts_menu -> ScheduleStatus.DRAFT
            R.id.schedule_filter_action_menu -> ScheduleStatus.NEEDS_ACTION
            R.id.schedule_filter_failed_menu -> ScheduleStatus.FAILED
            else -> return super.onOptionsItemSelected(item)
        }
        item.isChecked = true
        renderQueue()
        return true
    }

    private fun renderQueue() {
        showQueueMode()
        content.removeAllViews()
        addQueueViewFilters()
        val username = requestedUsername()
        val allPosts = if (username.isBlank()) store.list() else store.listForAccount(username)
        val posts = allPosts.filter { selectedQueueStatus == null || it.status == selectedQueueStatus }
        if (selectedQueueView == ScheduleQueueView.CALENDAR) {
            renderCalendar(posts)
        } else if (posts.isEmpty()) {
            content.addView(emptyState())
        } else {
            posts.forEach { content.addView(queueCard(it)) }
        }
        primaryButton.apply {
            visibility = View.VISIBLE
            isEnabled = !busy
            contentDescription = getString(R.string.schedule_new)
            setOnClickListener { openEditor(null) }
        }
        TwidgetFonts.applyTo(content)
    }

    private fun addQueueViewFilters() {
        content.addView(FloatingActionBar(this).apply {
            setButtonLabel(0, getString(R.string.schedule_view_list))
            setButtonLabel(1, getString(R.string.schedule_view_calendar))
            setButtonIcon(0, ContextCompat.getDrawable(context, OneUiIconR.drawable.ic_oui_list))
            setButtonIcon(1, ContextCompat.getDrawable(context, OneUiIconR.drawable.ic_oui_calendar))
            post {
                if (selectedQueueView == ScheduleQueueView.CALENDAR) setSelectedButton(1)
                setOnButtonSelectedListener { index ->
                    selectedQueueView = if (index == 0) ScheduleQueueView.LIST else ScheduleQueueView.CALENDAR
                    selectedCalendarDate = null
                    renderQueue()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(8), dp(10), dp(8), dp(20)) }
        })
    }

    private fun renderCalendar(posts: List<ScheduledPost>) {
        val zoneId = ZoneId.systemDefault()
        content.addView(calendarHeader())
        content.addView(calendarGrid(posts, zoneId))
    }

    private fun calendarHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(actionButton("‹") {
            calendarMonth = calendarMonth.minusMonths(1)
            selectedCalendarDate = null
            renderQueue()
        }.apply { contentDescription = getString(R.string.schedule_previous_month) })
        addView(TextView(this@ScheduleActivity).apply {
            text = calendarMonth.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())).uppercase(Locale.getDefault())
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = TwidgetFonts.oneUiSans(context, 700)
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(actionButton("›") {
            calendarMonth = calendarMonth.plusMonths(1)
            selectedCalendarDate = null
            renderQueue()
        }.apply { contentDescription = getString(R.string.schedule_next_month) })
    }

    private fun calendarGrid(posts: List<ScheduledPost>, zoneId: ZoneId): View = GridLayout(this).apply {
        columnCount = 7
        setPadding(dp(8), dp(4), dp(8), dp(12))
        val firstDay = calendarMonth.atDay(1)
        val leadingDays = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        val today = LocalDate.now()

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
            addView(calendarDayCell(null, emptyList()), calendarCellParams(position / 7 + 1, position % 7, dp(116)))
        }
        repeat(calendarMonth.lengthOfMonth()) { offset ->
            val date = calendarMonth.atDay(offset + 1)
            val position = leadingDays + offset
            val row = position / 7 + 1
            val column = position % 7
            val datedPosts = ScheduleCalendar.postsOnDate(posts, date, zoneId)
            addView(calendarDayCell(date, datedPosts), calendarCellParams(row, column, dp(116)))
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
        if (date == null) return@apply
        addView(TextView(this@ScheduleActivity).apply {
            text = date.dayOfMonth.toString()
            textSize = 14f
            typeface = TwidgetFonts.oneUiSans(context, 600)
            setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
        })
        posts.firstOrNull()?.let { post ->
            addView(TextView(this@ScheduleActivity).apply {
                text = post.thread.firstOrNull()?.text?.take(36)?.ifBlank { getString(R.string.schedule_media_post) }
                textSize = 8f
                maxLines = 4
                setTextColor(ContextCompat.getColor(context, R.color.oneui_text_primary))
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.schedule_calendar_post))
                }
                setOnClickListener { openEditor(post) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        }
        if (posts.size > 1) addView(TextView(this@ScheduleActivity).apply {
            text = "+${posts.size - 1}"
            textSize = 8f
            gravity = Gravity.END
        })
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

    private fun queueCard(post: ScheduledPost): View {
        val foreground = card().apply {
            setPadding(0, 0, 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(6), dp(4), dp(6), dp(12)) }
            val ready = post.status == ScheduleStatus.NEEDS_ACTION
            addView(CardItemView(this@ScheduleActivity).apply {
                title = if (ready) getString(R.string.schedule_ready_now) else queueDateTitle(post.scheduledAt)
                val body = post.thread.firstOrNull()?.text?.take(110)?.ifBlank {
                    getString(R.string.schedule_media_post)
                } ?: getString(R.string.schedule_empty_post)
                val mediaCount = post.thread.sumOf { it.media.size }
                summary = if (mediaCount == 0) body else "$body\n" + if (mediaCount == 1) {
                    getString(R.string.schedule_one_image_attached)
                } else {
                    getString(R.string.schedule_images_attached, mediaCount)
                }
                getTitleView().maxLines = 1
                getSummaryView().maxLines = 4
                getEndImageView()?.apply {
                    setImageResource(OneUiIconR.drawable.ic_oui_edit_outline)
                    contentDescription = getString(R.string.schedule_edit)
                    setOnClickListener { if (ready) renderChecklist(post) else openEditor(post) }
                }
                if (ready) {
                    icon = ContextCompat.getDrawable(context, R.drawable.schedule_ready_dot)
                    iconSize = dp(6)
                }
                setOnClickListener { if (ready) renderChecklist(post) else openEditor(post) }
            })
            post.errorMessage?.takeIf(String::isNotBlank)?.let {
                addView(metaText(getString(R.string.schedule_error_detail, it)).apply {
                    setTextColor(ContextCompat.getColor(context, R.color.metric_red))
                })
            }
        }

        return FrameLayout(this).apply {
            clipChildren = true
            addView(LinearLayout(this@ScheduleActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(swipeActionLabel(getString(R.string.schedule_duplicate), R.color.schedule_swipe_duplicate, Gravity.START))
                addView(swipeActionLabel(getString(R.string.delete), R.color.schedule_swipe_delete, Gravity.END))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(6), dp(4), dp(6), dp(12))
            })
            addView(foreground)
            bindQueueCardGestures(foreground, post)
        }
    }

    private fun swipeActionLabel(label: String, color: Int, gravity: Int): TextView = AppCompatButton(this).apply {
        text = label
        textSize = 14f
        typeface = TwidgetFonts.oneUiSans(context, 600)
        setTextColor(Color.WHITE)
        this.gravity = Gravity.CENTER_VERTICAL or gravity
        setPadding(dp(18), 0, dp(18), 0)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, color))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    }

    private fun bindQueueCardGestures(card: View, post: ScheduledPost) {
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        var dragging = false
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    downAt = System.currentTimeMillis()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!dragging && abs(dx) > dp(8) && abs(dx) > abs(event.rawY - downY)) {
                        dragging = true
                        view.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) view.translationX = dx.coerceIn(-dp(132).toFloat(), dp(132).toFloat())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - downX
                    when {
                        dragging && dx > dp(84) -> {
                            view.animate().translationX(0f).setDuration(160).start()
                            duplicate(post)
                        }
                        dragging && dx < -dp(84) -> {
                            view.animate().translationX(0f).setDuration(160).start()
                            deletePost(post)
                        }
                        !dragging && System.currentTimeMillis() - downAt > 500 -> showQueueMenu(view, post)
                        !dragging && post.status == ScheduleStatus.NEEDS_ACTION -> renderChecklist(post)
                        !dragging -> openEditor(post)
                        else -> view.animate().translationX(0f).setDuration(160).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showQueueMenu(anchor: View, post: ScheduledPost) {
        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.schedule_edit)).setOnMenuItemClickListener { openEditor(post); true }
            menu.add(getString(R.string.schedule_duplicate)).setOnMenuItemClickListener { duplicate(post); true }
            menu.add(getString(R.string.delete)).setOnMenuItemClickListener { deletePost(post); true }
            show()
        }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_delete_title)
            .setMessage(R.string.schedule_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val work = {
                    if (post.provider == ScheduleProvider.POSTPONE &&
                        !post.remotePostId.isNullOrBlank() &&
                        post.status != ScheduleStatus.CANCELLED
                    ) {
                        val result = coordinator.cancel(post.id)
                        if (result?.isSuccess == true) store.remove(post.id)
                        result
                    } else {
                        if (post.provider == ScheduleProvider.LOCAL_REMINDER) {
                            LocalReminderScheduler(this@ScheduleActivity).cancel(post.id)
                            ScheduleNotificationHelper.cancel(this@ScheduleActivity, post.id)
                        }
                        store.remove(post.id)
                        null
                    }
                }
                if (post.provider == ScheduleProvider.POSTPONE &&
                    !post.remotePostId.isNullOrBlank() &&
                    post.status != ScheduleStatus.CANCELLED
                ) {
                    runRemote(work)
                } else {
                    work()
                    renderQueue()
                }
            }
            .show()
    }

    private fun openEditor(post: ScheduledPost?) {
        startActivity(
            Intent(this, ScheduleComposeActivity::class.java)
                .putExtra(ScheduleComposeActivity.EXTRA_USERNAME, requestedUsername())
                .putExtra(ScheduleComposeActivity.EXTRA_SCHEDULE_ID, post?.id)
        )
    }

    private fun showQueueMode() {
        queueRoot.visibility = View.VISIBLE
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

    private fun emptyState(): View = card().apply {
        gravity = Gravity.CENTER
        addView(titleText(getString(R.string.schedule_empty_title)).apply {
            gravity = Gravity.CENTER
        })
        addView(metaText(getString(R.string.schedule_empty_summary)).apply {
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

    private fun View.updateBottomMargin(value: Int) {
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.bottomMargin = value
            layoutParams = it
        }
    }

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
    }
}
