package com.tjg.twidget.main

import android.content.Intent
import android.content.res.ColorStateList
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
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.ProfileImageLoader
import java.text.NumberFormat
import dev.oneuiproject.oneui.R as OneUiIconR

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
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
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
                topMargin = activity.dp(10)
            }) }

            addView(TextView(activity).apply {
                text = post?.let(::formattedPostText) ?: body.ifBlank { "--" }
                includeFontPadding = false
                setTextColor(activity.getColor(R.color.oneui_text_primary))
                textSize = 14f
                setLineSpacing(activity.dp(2).toFloat(), 1f)
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = activity.dp(10)
            })

            post?.media?.firstOrNull()?.let { media ->
                addView(ImageView(activity).apply {
                    contentDescription = media.alt.ifBlank { activity.getString(R.string.post_media) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    ProfileImageLoader.loadMediaInto(activity, this, media.url, activity.dp(14))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    activity.dp(218),
                ).apply {
                    topMargin = activity.dp(10)
                })
            }

            post?.let {
                addView(tweetMetricsRow(it), LinearLayout.LayoutParams(
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
            }, LinearLayout.LayoutParams(activity.dp(40), activity.dp(40)))

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

    private fun tweetMetricsRow(post: PostSummary): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addMetric(OneUiIconR.drawable.ic_oui_equalizer_2, post.views, R.string.post_metric_views)
            addMetric(OneUiIconR.drawable.ic_oui_message_outline, post.replies, R.string.post_metric_replies)
            addMetric(OneUiIconR.drawable.ic_oui_repeat, post.reposts, R.string.post_metric_reposts)
            addMetric(OneUiIconR.drawable.ic_oui_heart_outline, post.likes, R.string.post_metric_likes)
        }

    private fun LinearLayout.addMetric(iconRes: Int, value: Long, labelRes: Int) {
        val formattedValue = TwidgetStore.compactNumber(value)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            contentDescription = activity.getString(labelRes, formattedValue)
            addView(ImageView(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageDrawable(AppCompatResources.getDrawable(activity, iconRes))
                imageTintList = ColorStateList.valueOf(activity.getColor(R.color.oneui_text_primary))
            }, LinearLayout.LayoutParams(activity.dp(18), activity.dp(18)))
            addView(TextView(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                text = formattedValue
                includeFontPadding = false
                setTextColor(activity.getColor(R.color.oneui_text_primary))
                textSize = 14f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = activity.dp(4)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun postDate(post: PostSummary): String =
        if (post.timestamp > 0L) {
            AppLocales.formatDate(post.timestamp, "d. MMM, HH:mm", "MMM d, h:mm a")
        } else {
            post.createdAt
        }

}
