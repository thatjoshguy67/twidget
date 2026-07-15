package com.tjg.twidget.main

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.banger.BangerScanWorker
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.ProfileImageLoader
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MainPostAnalyticsBinder(
    private val activity: MainActivity,
) {
    fun createGridCard(card: DashboardCardType, account: String): View {
        val data = activity.analytics
        return when (card) {
            DashboardCardType.ALL_TIME_POST -> {
                var scanning = BangerScanWorker.isScanning(activity, account)
                if (data != null && data.banger == null && !scanning) {
                    BangerScanWorker.enqueue(activity, account)
                    scanning = true
                }
                val post = data?.banger
                when {
                    post != null -> postAnalyticsCard(
                        activity.getString(if (data.bangerComplete) R.string.all_time_banger else R.string.best_banger_found),
                        post,
                    )
                    scanning -> postAnalyticsShell(
                        activity.getString(R.string.finding_banger),
                        BangerScanWorker.postsScanned(activity, account).takeIf { it > 0 }
                            ?.let { activity.getString(R.string.banger_scanning_progress, NumberFormat.getIntegerInstance().format(it)) }
                            ?: activity.getString(R.string.banger_scanning),
                        null,
                    )
                    else -> emptyPostAnalyticsCard(account, R.string.all_time_banger)
                }
            }
            DashboardCardType.BEST_POST -> data?.best
                ?.let { postAnalyticsCard(activity.getString(if (data.postsAnalyzed == 1) R.string.only_post else R.string.best_post), it) }
                ?: emptyPostAnalyticsCard(account, R.string.best_post)
            DashboardCardType.WORST_POST -> data?.worst
                ?.let { postAnalyticsCard(activity.getString(R.string.worst_post), it) }
                ?: emptyPostAnalyticsCard(account, R.string.worst_post)
            else -> error("Not a post card")
        }
    }

    private fun emptyPostAnalyticsCard(account: String, labelRes: Int = R.string.post_analytics): View =
        postAnalyticsShell(activity.getString(labelRes), activity.getString(R.string.post_card_waiting, account), null)

    private fun postAnalyticsCard(label: String, post: PostSummary): View =
        postAnalyticsShell(label, post.text.ifBlank { post.url }, post)

    private fun postAnalyticsShell(label: String, body: String, post: PostSummary?): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(14), activity.dp(16), activity.dp(14))
            val opensPost = post?.url?.isNotBlank() == true
            background = AppCompatResources.getDrawable(
                activity,
                if (opensPost) R.drawable.metric_card_clickable_bg else R.drawable.metric_card_bg,
            )
            isClickable = opensPost
            isFocusable = opensPost
            contentDescription = if (post == null) label else activity.getString(R.string.open_post)
            post?.url?.takeIf { it.isNotBlank() }?.let { postUrl ->
                setOnClickListener {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(postUrl)))
                }
            }

            addView(TextView(activity).apply {
                text = label
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(activity.getColor(R.color.oneui_text_secondary))
                textSize = 13f
                typeface = Typeface.create("sec", Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))

            post?.let { addView(tweetAuthorRow(it), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = activity.dp(11)
            }) }

            addView(TextView(activity).apply {
                text = post?.let(::formattedPostText) ?: body.ifBlank { "--" }
                includeFontPadding = false
                maxLines = 4
                setTextColor(activity.getColor(R.color.oneui_text_primary))
                textSize = 15f
                setLineSpacing(activity.dp(2).toFloat(), 1f)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = activity.dp(7)
            })

            post?.media?.firstOrNull()?.let { media ->
                addView(ImageView(activity).apply {
                    contentDescription = media.alt.ifBlank { activity.getString(R.string.post_media) }
                    ProfileImageLoader.loadMediaInto(activity, this, media.url, activity.dp(14))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dp(120),
                ).apply {
                    topMargin = activity.dp(12)
                })
            }

            post?.let {
                addView(TextView(activity).apply {
                    text = tweetMetrics(it)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(activity.getColor(R.color.oneui_text_secondary))
                    textSize = 13f
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = activity.dp(10)
                })
            }
        }

    private fun tweetAuthorRow(post: PostSummary): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(ImageView(activity).apply {
                ProfileImageLoader.loadInto(activity, this, post.authorAvatar)
            }, LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)))

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(10), 0, 0, 0)
                addView(TextView(activity).apply {
                    text = post.authorName.ifBlank { post.authorUserName.ifBlank { activity.getString(R.string.app_name) } }
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(activity.getColor(R.color.oneui_text_primary))
                    textSize = 14f
                    typeface = Typeface.create("sec", Typeface.BOLD)
                })
                addView(TextView(activity).apply {
                    text = activity.getString(
                        R.string.tweet_handle_and_date,
                        post.authorUserName.ifBlank { "x" },
                        postDate(post),
                    )
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(activity.getColor(R.color.oneui_text_secondary))
                    textSize = 12f
                    setPadding(0, activity.dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun formattedPostText(post: PostSummary): CharSequence {
        val spannable = SpannableString(post.text.ifBlank { post.url })
        post.links.forEach { link ->
            val start = spannable.indexOf(link.display)
            if (start < 0) return@forEach
            val end = start + link.display.length
            spannable.setSpan(URLSpan(link.url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(activity.getColor(R.color.oneui_accent)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return spannable
    }

    private fun postDate(post: PostSummary): String =
        if (post.timestamp > 0L) {
            SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(post.timestamp))
        } else {
            post.createdAt
        }

    private fun tweetMetrics(post: PostSummary): String =
        activity.getString(
            R.string.tweet_metrics,
            TwidgetStore.compactNumber(post.views),
            TwidgetStore.compactNumber(post.replies),
            TwidgetStore.compactNumber(post.likes),
        )
}
