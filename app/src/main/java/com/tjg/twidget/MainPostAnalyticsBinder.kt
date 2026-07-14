package com.tjg.twidget

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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class MainPostAnalyticsBinder(
    private val activity: MainActivity,
) {
    fun bindBestWorst(page: View, account: String) {
        val section = page.findViewById<LinearLayout>(R.id.post_analytics_section) ?: return
        val cards = page.findViewById<LinearLayout>(R.id.post_analytics_cards) ?: return
        cards.removeAllViews()

        val data = activity.analytics
        var bangerScanning = BangerScanWorker.isScanning(activity, account)
        if (data != null && data.banger == null && !bangerScanning) {
            BangerScanWorker.enqueue(activity, account)
            bangerScanning = true
        }
        val bangerProgress = BangerScanWorker.postsScanned(activity, account)
        val posts = listOfNotNull(
            data?.banger?.let {
                activity.getString(if (data.bangerComplete) R.string.all_time_banger else R.string.best_banger_found) to it
            },
            data?.best?.let {
                activity.getString(if (data.postsAnalyzed == 1) R.string.only_post else R.string.best_post) to it
            },
            data?.worst?.let { activity.getString(R.string.worst_post) to it },
        )
        if (posts.isEmpty() && !bangerScanning) {
            section.visibility = if (data == null) View.GONE else View.VISIBLE
            if (data != null) cards.addView(emptyPostAnalyticsCard(account))
            return
        }

        section.visibility = View.VISIBLE
        if (bangerScanning) {
            val progress = if (bangerProgress > 0) {
                activity.getString(R.string.banger_scanning_progress, NumberFormat.getIntegerInstance().format(bangerProgress))
            } else {
                activity.getString(R.string.banger_scanning)
            }
            cards.addView(postAnalyticsShell(activity.getString(R.string.finding_banger), progress, null))
        }
        posts.forEachIndexed { index, (label, post) ->
            cards.addView(postAnalyticsCard(label, post), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0 || bangerScanning) topMargin = activity.dp(8)
            })
        }
    }

    private fun emptyPostAnalyticsCard(account: String): View =
        postAnalyticsShell(activity.getString(R.string.post_analytics), "@$account", null)

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
                maxLines = 6
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
                    activity.dp(190),
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
