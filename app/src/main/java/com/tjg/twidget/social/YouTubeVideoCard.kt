package com.tjg.twidget.social

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.ProfileImageLoader

internal object YouTubeVideoCard {
    fun create(context: Context, account: PlatformAccount): View = with(context) {
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
            text = value; textSize = size; setTextColor(getColor(if (bold) R.color.oneui_text_primary else R.color.oneui_text_secondary))
            if (bold) typeface = Typeface.create("sec", Typeface.BOLD)
        }
        val snapshot = YouTubeVideoCache.read(this, account.id)
        val video = snapshot?.best(System.currentTimeMillis())
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "video:${account.id}"
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(ImageView(context).apply {
                    setImageDrawable(SocialPlatform.YOUTUBE.icon(context)); contentDescription = SocialPlatform.YOUTUBE.label
                    imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.oneui_text_secondary))
                }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
                addView(label(getString(if (snapshot?.complete == false) R.string.youtube_top_recent_video else R.string.youtube_best_video), 13f, true).apply {
                    setTextColor(getColor(R.color.oneui_text_secondary)); maxLines = 2
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
                setBackgroundResource(R.drawable.metric_card_bg)
                if (video == null) {
                    minimumHeight = dp(140); gravity = Gravity.CENTER
                    addView(label(getString(if (snapshot == null) R.string.youtube_video_unavailable else R.string.youtube_no_recent_video), 14f))
                } else {
                    isClickable = true; isFocusable = true
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${video.id}"))) }
                    addView(ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = video.title
                        ProfileImageLoader.loadMediaInto(context, this, video.thumbnail, dp(14))
                    }, LinearLayout.LayoutParams(-1, dp(210)))
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.TOP; setPadding(0, dp(10), 0, 0)
                        addView(ImageView(context).apply {
                            contentDescription = null; ProfileImageLoader.loadInto(context, this, account.avatarUrl)
                        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(video.title, 14f, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                            addView(label(account.displayName, 12f))
                            addView(label(getString(R.string.youtube_video_views, TwidgetStore.compactNumber(video.views),
                                DateUtils.getRelativeTimeSpanString(video.publishedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)), 12f))
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    })
                    addView(label(getString(if (snapshot.complete) R.string.youtube_video_basis else R.string.youtube_video_partial_basis), 12f).apply { setPadding(0, dp(8), 0, 0) })
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }
}
