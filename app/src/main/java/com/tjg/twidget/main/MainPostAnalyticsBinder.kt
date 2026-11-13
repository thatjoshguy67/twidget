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
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.banger.BangerClient
import com.tjg.twidget.banger.BangerScanWorker
import com.tjg.twidget.brief.BriefReviewTarget
import com.tjg.twidget.brief.BriefTweetReviewActivity
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startRightSidePopOverActivity
import java.text.NumberFormat
import kotlin.math.roundToInt
import dev.oneuiproject.oneui.R as OneUiIconR

internal class MainPostAnalyticsBinder(
    private val activity: MainActivity,
) {
    private fun cardDp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()

    fun createGridCard(card: DashboardCardType, account: String): View {
        val data = activity.analytics
        return when (card) {
            DashboardCardType.ALL_TIME_POST -> {
                var scanning = BangerScanWorker.isScanning(activity, account)
                if (data != null && (data.banger == null || BangerClient.needsRescan(activity, account)) && !scanning) {
                    BangerScanWorker.enqueue(activity, account)
                    scanning = true
                }
                val post = data?.banger
                when {
                    post != null -> postAnalyticsCard(
                        activity.getString(if (data.bangerComplete) R.string.all_time_banger else R.string.best_banger_found),
                        post,
                        account,
                    )
                    scanning -> postAnalyticsShell(
                        activity.getString(R.string.finding_banger),
                        BangerScanWorker.postsScanned(activity, account).takeIf { it > 0 }
                            ?.let { activity.getString(R.string.banger_scanning_progress, NumberFormat.getIntegerInstance(com.tjg.twidget.core.AppLocales.applicationLocale()).format(it)) }
                            ?: activity.getString(R.string.banger_scanning),
                        null,
                    )
                    else -> emptyPostAnalyticsCard(account, R.string.all_time_banger)
                }
            }
            DashboardCardType.BEST_POST -> data?.best
                ?.let { postAnalyticsCard(activity.getString(if (data.postsAnalyzed == 1 && data.recentPosts.singleOrNull()?.url == it.url) R.string.only_post else R.string.best_post), it, account) }
                ?: emptyPostAnalyticsCard(account, R.string.best_post)
            DashboardCardType.WORST_POST -> data?.worst
                ?.let { postAnalyticsCard(activity.getString(R.string.worst_post), it, account) }
                ?: emptyPostAnalyticsCard(account, R.string.worst_post)
            else -> error("Not a post card")
        }
    }

    private fun emptyPostAnalyticsCard(account: String, labelRes: Int = R.string.post_analytics): View =
        postAnalyticsShell(activity.getString(labelRes), activity.getString(R.string.post_card_waiting, account), null)

    private fun postAnalyticsCard(label: String, post: PostSummary, account: String): View =
        postAnalyticsShell(label, post.text.ifBlank { post.url }, post, account)

    private fun postAnalyticsShell(label: String, body: String, post: PostSummary?, account: String = ""): View {
        val surface = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardDp(12), cardDp(12), cardDp(12), cardDp(12))
            clipToOutline = true
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
                    activity.dashboardTweetLauncher.open(postUrl)
                }
            }


            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
                minimumHeight = cardDp(24)
                addView(TextView(activity).apply {
                    id = R.id.dashboard_post_heading
                    text = label
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    textSize = 14f
                    TwidgetFonts.dashboardHeading(this)
                    gravity = Gravity.CENTER_VERTICAL or if (post == null) Gravity.CENTER_HORIZONTAL else Gravity.START
                    setTextColor(activity.getColor(R.color.oneui_text_secondary))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                if (post != null) {
                    val button = LayoutInflater.from(activity).inflate(R.layout.view_dashboard_report_button, this, false)
                    button.setTag(R.id.dashboard_design_line_height, 16f)
                    (button as? TextView)?.typeface = TwidgetFonts.forApp(activity, 700)
                    button.setOnClickListener {
                        activity.startRightSidePopOverActivity(BriefTweetReviewActivity.intent(activity,
                            BriefReviewTarget(account, postUrl = post.url, fromDashboard = true)))
                    }
                    addView(button)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(View(activity).apply {
                id = R.id.dashboard_post_divider
                background = android.graphics.drawable.ColorDrawable(activity.getColor(R.color.oneui_divider))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, cardDp(1),
            ).apply { topMargin = cardDp(8); bottomMargin = cardDp(8) })


            val contentRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            val copy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(com.tjg.twidget.ui.DashboardFadingTextView(activity).apply {
                id = R.id.dashboard_post_body
                text = post?.let(::formattedPostText) ?: body.ifBlank { "--" }
                includeFontPadding = false
                setTextColor(activity.getColor(R.color.oneui_text_primary))
                textSize = 13f
                typeface = TwidgetFonts.forApp(activity, 400)
                maxLines = Int.MAX_VALUE
                ellipsize = null
                setTag(R.id.dashboard_design_line_height, 18f)
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            contentRow.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            post?.media?.takeIf { it.isNotEmpty() }?.let { media ->
                contentRow.addView(com.tjg.twidget.ui.DashboardPostMediaLayout(activity, media.size > 1).apply {
                    id = R.id.dashboard_post_media
                    clipChildren = false
                    clipToPadding = false
                    media.take(if (media.size > 1) 2 else 1).forEachIndexed { index, item ->
                        addView(com.tjg.twidget.ui.DashboardShadowLayout(activity).apply {
                            rotation = if (media.size > 1) if (index == 0) -3f else 3f else 0f
                            translationX = if (media.size > 1) cardDp(if (index == 0) -5 else 5).toFloat() else 0f
                            addView(ImageView(activity).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                contentDescription = item.alt.ifBlank { activity.getString(R.string.post_media) }
                                ProfileImageLoader.loadMediaInto(activity, this, item.url, cardDp(8))
                            }, android.widget.FrameLayout.LayoutParams(-1, -1))
                        }, android.widget.FrameLayout.LayoutParams(cardDp(112), cardDp(88), Gravity.CENTER))
                    }
                    contentDescription = activity.getString(R.string.post_media) + ": " + media.size
                },
                    LinearLayout.LayoutParams(cardDp(151), LinearLayout.LayoutParams.MATCH_PARENT))
            }
            addView(contentRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        }

        // Expand the hit area into the card's existing padding instead of making the stats row taller.
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            surface.findViewById<View>(R.id.dashboard_view_report)?.let { button ->
                val bounds = android.graphics.Rect(0, 0, button.width, button.height)
                surface.offsetDescendantRectToMyCoords(button, bounds)
                bounds.inset(0, -maxOf(0, (cardDp(48) - bounds.height()) / 2))
                bounds.top = bounds.top.coerceAtLeast(0)
                bounds.bottom = bounds.bottom.coerceAtMost(surface.height)
                surface.touchDelegate = android.view.TouchDelegate(bounds, button)
            }
        }
        return surface
    }

    private fun tweetFooter(post: PostSummary, account: String): View {
        val footer = PostFooter(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        val metrics = tweetMetricsRow(post)
        val button = LayoutInflater.from(activity).inflate(R.layout.view_dashboard_report_button, footer, false)
            as androidx.appcompat.widget.AppCompatButton
        button.typeface = TwidgetFonts.forApp(activity, 700)
        button.setOnClickListener {
            activity.startRightSidePopOverActivity(BriefTweetReviewActivity.intent(activity,
                BriefReviewTarget(account, postUrl = post.url, fromDashboard = true)))
        }
        // The card delegates a larger touch area; this wrapper only lays out the compact pill.
        val action = android.widget.FrameLayout(activity).apply {
            addView(button, android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        footer.addView(metrics, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = activity.dp(8)
        })
        return footer
    }

    /** Measure natural widths before sharing space so large type never clips the stats. */
    private class PostFooter(context: android.content.Context) : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (childCount == 2) {
                val natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                val metrics = getChildAt(0)
                val action = getChildAt(1)
                metrics.measure(natural, natural)
                action.measure(natural, natural)
                val gap = (8 * resources.displayMetrics.density).toInt()
                val stacked = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
                    metrics.measuredWidth + action.measuredWidth + gap > MeasureSpec.getSize(widthMeasureSpec)
                orientation = if (stacked) VERTICAL else HORIZONTAL
                (metrics.layoutParams as LayoutParams).apply {
                    width = if (stacked) LayoutParams.MATCH_PARENT else 0
                    weight = if (stacked) 0f else 1f
                }
                (action.layoutParams as LayoutParams).apply {
                    gravity = Gravity.END
                    marginStart = if (stacked) 0 else gap
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun formattedPostText(post: PostSummary): CharSequence {
        val paragraphs = post.text.ifBlank { post.url }.replace(Regex("\n[ \t]*\n+"), "\u2029")
        val spannable = SpannableString(paragraphs.replace('\u2029', '\n'))
        paragraphs.forEachIndexed { index, char ->
            if (char == '\u2029') spannable.setSpan(object : android.text.style.LineHeightSpan.WithDensity {
                override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int,
                    fm: android.graphics.Paint.FontMetricsInt) = Unit
                override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int,
                    fm: android.graphics.Paint.FontMetricsInt, paint: android.text.TextPaint) {
                    val gap = (paint.textSize * 4f / 14f).toInt()
                    fm.descent += gap
                    fm.bottom += gap
                }
            }, index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }


}
