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
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.startLeftSidePopOverActivity
import com.tjg.twidget.update.ReleaseNotice
import dev.oneuiproject.oneui.widget.RoundedNestedScrollView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class NoticeDetailActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notice_detail)
        val root = findViewById<FrameLayout>(R.id.notice_detail_root)
        val scroll = findViewById<RoundedNestedScrollView>(R.id.notice_detail_scroll)
        val back = findViewById<FrameLayout>(R.id.notice_detail_back)
        back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            back.layoutParams = (back.layoutParams as FrameLayout.LayoutParams).apply {
                marginStart = safe.left + dp(18)
                topMargin = safe.top + dp(18)
            }
            scroll.setPadding(safe.left, 0, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val tag = intent.getStringExtra(EXTRA_NOTICE_TAG)
        val notice = ReleaseNoticesStore.cached(this).notices
            .firstOrNull { it.tag == tag }
        if (notice == null) {
            finish()
            return
        }

        findViewById<TextView>(R.id.notice_detail_title).text = notice.title
        findViewById<TextView>(R.id.notice_detail_meta).text = releaseMeta(notice)
        findViewById<TextView>(R.id.notice_detail_beta).visibility =
            if (notice.prerelease) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.notice_detail_body).apply {
            text = if (notice.body.isBlank()) {
                getString(R.string.notices_no_details)
            } else {
                ReleaseNoticeMarkdown.render(this@NoticeDetailActivity, notice.body)
            }
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = android.graphics.Color.TRANSPARENT
        }
        findViewById<AppCompatButton>(R.id.notice_detail_release_button).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.url)))
        }
        findViewById<AppCompatButton>(R.id.notice_detail_update_button).setOnClickListener {
            startLeftSidePopOverActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun releaseMeta(notice: ReleaseNotice): String {
        val date = runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .format(Instant.parse(notice.publishedAt).atZone(ZoneId.systemDefault()))
        }.getOrNull()
        return listOfNotNull(notice.tag, date).joinToString(" · ")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_NOTICE_TAG = "notice_tag"
    }
}
