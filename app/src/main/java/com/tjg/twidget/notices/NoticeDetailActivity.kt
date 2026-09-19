package com.tjg.twidget.notices

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.startLeftSidePopOverActivity
import com.tjg.twidget.update.ReleaseNotice
import com.tjg.twidget.update.AppUpdateManager
import dev.oneuiproject.oneui.widget.RoundedNestedScrollView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class NoticeDetailActivity : FoldablePopOverActivity() {
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notice_detail)
        val root = findViewById<FrameLayout>(R.id.notice_detail_root)
        val scroll = findViewById<RoundedNestedScrollView>(R.id.notice_detail_scroll)
        val back = findViewById<FrameLayout>(R.id.notice_detail_back)
        NoticeReaderChrome.install(back) { onBackPressedDispatcher.onBackPressed() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            NoticeReaderChrome.updateInsets(back, safe.left, safe.top, safe.right)
            scroll.setPadding(safe.left, 0, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        if (intent.getBooleanExtra(EXTRA_LATEST, false)) {
            loadLatest()
            return
        }
        val tag = intent.getStringExtra(EXTRA_NOTICE_TAG)
        val notice = ReleaseNoticesStore.visible(this)
            .firstOrNull { it.tag == tag }
            ?: UpcomingReleaseNotes.read(this)?.takeIf { it.tag == tag }
        if (notice == null) {
            finish()
            return
        }

        showNotice(notice)
    }

    private fun loadLatest() {
        if (loading) return
        loading = true
        showLatestStatus(R.string.notices_loading, retry = false)
        AppExecutors.execute(onRejected = {
            loading = false
            showLatestStatus(R.string.notices_load_failed, retry = true)
        }) {
            val result = runCatching {
                LatestReleaseNotice.load(ReleaseNoticesStore.visible(applicationContext).firstOrNull()) {
                    val notices = AppUpdateManager.fetchReleaseNotices()
                    ReleaseNoticesStore.save(applicationContext, notices)
                    ReleaseNoticesStore.visible(applicationContext).firstOrNull()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                val notice = result.getOrNull()
                if (notice != null) showNotice(notice)
                else showLatestStatus(if (result.isFailure) R.string.notices_load_failed else R.string.notices_empty_title, retry = true)
            }
        }
    }

    private fun showLatestStatus(message: Int, retry: Boolean) {
        findViewById<TextView>(R.id.notice_detail_title).setText(R.string.notices_changelog_title)
        findViewById<TextView>(R.id.notice_detail_body).setText(message)
        findViewById<View>(R.id.notice_detail_meta).visibility = View.GONE
        findViewById<View>(R.id.notice_detail_beta).visibility = View.GONE
        findViewById<View>(R.id.notice_detail_actions_divider).visibility = View.GONE
        findViewById<View>(R.id.notice_detail_update_button).visibility = View.GONE
        findViewById<AppCompatButton>(R.id.notice_detail_release_button).apply {
            visibility = if (retry) View.VISIBLE else View.GONE
            setText(R.string.notices_retry)
            setOnClickListener { loadLatest() }
        }
    }

    private fun showNotice(notice: ReleaseNotice) {
        if (intent.getBooleanExtra(EXTRA_LATEST, false)) ReleaseNoticesStore.markCurrentAsSeen(this)
        findViewById<TextView>(R.id.notice_detail_title).text = notice.title
        findViewById<View>(R.id.notice_detail_meta).visibility = View.VISIBLE
        findViewById<TextView>(R.id.notice_detail_meta).text = releaseMeta(notice)
        findViewById<TextView>(R.id.notice_detail_beta).apply {
            setText(if (notice.upcoming) R.string.notices_upcoming else R.string.notices_beta)
            visibility = if (notice.prerelease || notice.upcoming) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.notice_detail_body).apply {
            text = if (notice.body.isBlank()) {
                getString(R.string.notices_no_details)
            } else {
                ReleaseNoticeMarkdown.render(this@NoticeDetailActivity, notice.body)
            }
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = android.graphics.Color.TRANSPARENT
        }
        findViewById<AppCompatButton>(R.id.notice_detail_release_button).setText(R.string.notices_view_on_github)
        findViewById<AppCompatButton>(R.id.notice_detail_release_button).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.url)))
        }
        findViewById<AppCompatButton>(R.id.notice_detail_update_button).setOnClickListener {
            startLeftSidePopOverActivity(Intent(this, AboutActivity::class.java))
        }
        listOf(R.id.notice_detail_actions_divider, R.id.notice_detail_release_button, R.id.notice_detail_update_button).forEach {
            findViewById<View>(it).visibility = if (notice.upcoming) View.GONE else View.VISIBLE
        }
    }

    private fun releaseMeta(notice: ReleaseNotice): String {
        if (notice.upcoming) return getString(R.string.notices_upcoming_summary)
        val date = runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .format(Instant.parse(notice.publishedAt).atZone(ZoneId.systemDefault()))
        }.getOrNull()
        return listOfNotNull(notice.tag, date).joinToString(" · ")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NOTICE_TAG = "notice_tag"
        const val EXTRA_LATEST = "latest_notice"
    }
}
