package com.tjg.twidget.notices

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.OneUiSpinner
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startRightSidePopOverActivity
import com.tjg.twidget.update.AppUpdateManager
import com.tjg.twidget.update.ReleaseNotice
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class NoticesActivity : FoldablePopOverActivity() {
    private lateinit var content: LinearLayout
    private lateinit var refresh: SwipeRefreshLayout
    private var generation = 0
    private var notices = emptyList<ReleaseNotice>()
    private var errorMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notices)
        content = findViewById(R.id.notices_content)
        refresh = findViewById<SwipeRefreshLayout>(R.id.notices_refresh).apply {
            OneUiSpinner.attachToSwipeRefresh(this)
            setOnRefreshListener { refreshNotices() }
        }
        findViewById<ToolbarLayout>(R.id.notices_root)
            .setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(findViewById(R.id.notices_root))

        ReleaseNoticesStore.cached(this).let {
            notices = it.notices
        }
        ReleaseNoticesStore.markCurrentAsSeen(this)
        render()
        refreshNotices()
    }

    override fun onDestroy() {
        generation++
        super.onDestroy()
    }

    private fun refreshNotices() {
        val token = ++generation
        refresh.isRefreshing = true
        errorMessage = null
        AppExecutors.execute(onRejected = {
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                refresh.isRefreshing = false
                errorMessage = getString(R.string.notices_load_failed)
                render()
            }
        }) {
            val result = runCatching { AppUpdateManager.fetchReleaseNotices() }
            result.getOrNull()?.let {
                ReleaseNoticesStore.save(applicationContext, it)
                ReleaseNoticesStore.markCurrentAsSeen(applicationContext)
            }
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                refresh.isRefreshing = false
                result.onSuccess {
                    notices = it
                }.onFailure {
                    errorMessage = getString(R.string.notices_load_failed)
                }
                render()
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        errorMessage?.let {
            content.addView(card().apply {
                addView(titleText(it))
                if (notices.isNotEmpty()) addView(metaText(getString(R.string.notices_showing_cache)))
            })
        }
        if (notices.isEmpty()) {
            if (!refresh.isRefreshing) content.addView(emptyState())
        } else {
            notices.forEach { content.addView(noticeCard(it)) }
        }
        TwidgetFonts.applyTo(content)
    }

    private fun noticeCard(notice: ReleaseNotice): View = card().apply {
        background = AppCompatResources.getDrawable(context, R.drawable.metric_card_clickable_bg)
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.notices_open_changelog, notice.title)
        setOnClickListener {
            startRightSidePopOverActivity(
                Intent(this@NoticesActivity, NoticeDetailActivity::class.java)
                    .putExtra(NoticeDetailActivity.EXTRA_NOTICE_TAG, notice.tag)
            )
        }

        addView(LinearLayout(this@NoticesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleText(notice.title), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (notice.prerelease) {
                addView(TextView(this@NoticesActivity).apply {
                    setText(R.string.notices_beta)
                    textSize = 12f
                    typeface = Typeface.create("sec", Typeface.BOLD)
                    setTextColor(getColor(R.color.oneui_accent))
                    setPadding(dp(8), 0, 0, 0)
                })
            }
        })
        addView(metaText(releaseMeta(notice)))
        val body = ReleaseNoticeText.plainText(notice.body)
        addView(TextView(this@NoticesActivity).apply {
            text = body.ifBlank { getString(R.string.notices_no_details) }
            textSize = 14f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(getColor(R.color.oneui_text_primary))
            setPadding(0, dp(12), 0, 0)
        })
        addView(metaText(getString(R.string.notices_view_changelog)).apply {
            setTextColor(getColor(R.color.oneui_accent))
        })
    }

    private fun releaseMeta(notice: ReleaseNotice): String {
        val date = runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .format(Instant.parse(notice.publishedAt).atZone(ZoneId.systemDefault()))
        }.getOrNull()
        return listOfNotNull(notice.tag, date).joinToString(" · ")
    }

    private fun emptyState(): View = card().apply {
        gravity = Gravity.CENTER
        addView(titleText(getString(R.string.notices_empty_title)).apply { gravity = Gravity.CENTER })
        addView(metaText(getString(R.string.notices_empty_summary)).apply { gravity = Gravity.CENTER })
        addView(AppCompatButton(this@NoticesActivity).apply {
            setText(R.string.notices_retry)
            isAllCaps = false
            setOnClickListener { refreshNotices() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            topMargin = dp(12)
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AppCompatResources.getDrawable(context, R.drawable.schedule_card_bg)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(6), 0, dp(6), dp(10)) }
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        typeface = TwidgetFonts.oneUiSans(context, 700)
        setTextColor(getColor(R.color.oneui_text_primary))
    }

    private fun metaText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(getColor(R.color.oneui_text_secondary))
        setPadding(0, dp(6), 0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
