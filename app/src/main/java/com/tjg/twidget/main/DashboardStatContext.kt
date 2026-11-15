package com.tjg.twidget.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ActivityClient
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.DailyStreakStore
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetFonts
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

internal object DashboardPeriods {
    fun label(history: List<HistorySample>): String {
        val known = history.filterNot { it.estimated }.sortedBy { it.timestamp }
        if (known.isEmpty()) return ""
        val first = AppLocales.formatDate(known.first().timestamp, "MMMd")
        val last = AppLocales.formatDate(known.last().timestamp, "MMMd")
        return if (first == last) first else "$first–$last"
    }
}

/** Extra space carries evidence, with explicit sample/period labels. */
internal object DashboardStatContext {
    fun bind(activity: MainActivity, card: DashboardCardType, root: View, stats: ProfileStats,
        history: List<HistorySample>, expanded: Boolean) {
        root.setTag(R.id.dashboard_stat_expanded, expanded)
        val panel = root.findViewById<LinearLayout>(R.id.dashboard_stat_context) ?: return
        panel.removeAllViews()
        val descriptions = mutableListOf<String>()
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).roundToInt()
        fun text(value: String, size: Float = 12f, strong: Boolean = false) = TextView(activity).apply {
            text = value; textSize = size; includeFontPadding = false
            typeface = TwidgetFonts.forApp(activity, if (strong) 700 else 400)
            setTextColor(activity.getColor(if (strong) R.color.oneui_text_primary else R.color.oneui_text_secondary))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fun caption(value: String) {
            if (value.isBlank()) return
            descriptions += value
            panel.addView(text(value), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        var rowCount = 0
        val rowLimit = if (activity.resources.configuration.fontScale >= 1.6f) 1 else if (activity.resources.configuration.fontScale >= 1.3f) 2 else 3
        fun row(label: String, value: String) {
            if (rowCount++ >= rowLimit) return
            descriptions += "$label: $value"
            panel.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = true
                addView(text(label), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                addView(text(value, 14f, true), LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        fun string(id: Int) = activity.getString(id)
        fun count(n: Long) = TwidgetStore.compactNumber(n)
        fun bars(values: List<Double?>, labels: List<String>) {
            if (values.isEmpty() || values.all { it == null } || activity.resources.configuration.fontScale >= 1.6f) return
            panel.addView(MiniBars(activity, values, activity.getColor(R.color.oneui_accent)).apply {
                contentDescription = labels.zip(values).joinToString(". ") { (label, v) -> "$label: ${v?.let { count(it.toLong()) } ?: "--"}" }
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(6) })
        }
        val data = activity.analytics
        val posts = data?.recentPosts.orEmpty().sortedBy { it.timestamp }
        val real = history.filterNot { it.estimated }.sortedBy { it.timestamp }
        val period = DashboardPeriods.label(real)
        when (card) {
            DashboardCardType.FOLLOWER_RATIO, DashboardCardType.AUDIENCE_BALANCE -> {
                val maximum = maxOf(stats.followersCount, stats.followingsCount, 1L)
                fun comparison(label: String, known: Boolean, amount: Long) {
                    descriptions += "$label: ${if (known) amount else "--"}"
                    val group = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    group.addView(LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = true
                        addView(text(label, 14f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                        addView(text(if (known) count(amount) else "--", 16f, true), LinearLayout.LayoutParams(-2, -2))
                    })
                    if (known && activity.resources.configuration.fontScale < 1.6f) {
                        group.addView(ComparisonBar(activity, amount.toFloat() / maximum,
                            activity.getColor(R.color.oneui_accent)).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                            LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(6) })
                    }
                    panel.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                }
                comparison(string(R.string.followers), stats.followersKnown, stats.followersCount)
                comparison(string(R.string.following), stats.followingKnown, stats.followingsCount)
            }
            DashboardCardType.DAILY_STREAK -> {
                val snapshot = ActivityClient.snapshot(activity, stats.userName)
                val days = DailyStreakStore.activeDays(activity, stats.userName)
                val week = (6L downTo 0L).map { LocalDate.now().minusDays(it) }
                caption(string(R.string.dashboard_activity_week))
                bars(week.map { day -> when {
                    day.toString() in days -> 1.0
                    snapshot.activityComplete && day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() >= snapshot.activityWindowStartAt -> 0.0
                    else -> null
                } }, week.map { it.toString() })
                row(string(R.string.dashboard_best_streak), snapshot.longestStreak.toString())
            }
            DashboardCardType.POST_RATE -> {
                caption(period)
                val pairs = real.zipWithNext().filter { (a,b) -> a.postsKnown && b.postsKnown }
                bars(pairs.map { (a,b) -> (b.posts - a.posts).toDouble() }, pairs.map { it.second.dayLabel })
                val known = real.filter { it.postsKnown }
                row(string(R.string.dashboard_net_posts), if (known.size >= 2) TwidgetStore.signedNumber(known.last().posts - known.first().posts) else "--")
            }
            DashboardCardType.ENGAGEMENT_RATE, DashboardCardType.AVG_ENGAGEMENTS, DashboardCardType.MEDIAN_ENGAGEMENTS -> {
                caption(if (data == null) string(R.string.analytics_syncing) else string(R.string.dashboard_recent_posts))
                if (posts.isNotEmpty()) {
                    row(string(R.string.dashboard_likes), count(posts.sumOf { it.likes }))
                    row(string(R.string.dashboard_replies), count(posts.sumOf { it.replies }))
                    row(string(R.string.dashboard_reposts), count(posts.sumOf { it.reposts + it.quotes }))
                }
            }
            DashboardCardType.AVG_VIEWS -> {
                caption(if (data == null) string(R.string.analytics_syncing) else string(R.string.dashboard_recent_posts))
                bars(posts.takeLast(14).map { if (it.viewsKnown) it.views.toDouble() else null }, posts.takeLast(14).map { AppLocales.formatDate(it.timestamp, "MMMd") })
                val observedViews = posts.filter { it.viewsKnown }.map { it.views }.sorted()
                val median = if (observedViews.isEmpty()) null else if (observedViews.size % 2 == 1) observedViews[observedViews.size / 2]
                    else ((observedViews[observedViews.size / 2 - 1].toDouble() + observedViews[observedViews.size / 2]) / 2).toLong()
                row(string(R.string.dashboard_median), median?.let(::count) ?: "--")
            }
            DashboardCardType.TOTAL_VIEWS -> {
                caption(if (data == null) string(R.string.analytics_syncing) else string(R.string.dashboard_recent_posts))
                val top = posts.filter { it.viewsKnown }.sortedByDescending { it.views }.take(3)
                top.forEachIndexed { i, post -> row(post.timestamp.takeIf { it > 0 }?.let { AppLocales.formatDate(it, "MMMd") } ?: activity.getString(R.string.dashboard_post_rank, i + 1), count(post.views)) }
            }
            DashboardCardType.LIKES_PER_POST -> {
                row(string(R.string.dashboard_likes), if (stats.likesKnown) count(stats.likeCount) else "--")
                row(string(R.string.posts), if (stats.postsKnown) count(stats.statusesCount) else "--")
            }
            DashboardCardType.X_IMPRESSIONS, DashboardCardType.X_ENGAGEMENTS,
            DashboardCardType.X_PROFILE_VISITS, DashboardCardType.X_LIKES_RECEIVED -> {
                val imports = activity.importedAnalytics.sortedBy { it.date }
                caption(string(R.string.dashboard_imported))
                row(string(R.string.dashboard_days), imports.size.toString())
                imports.lastOrNull()?.let { caption(it.date.toString()) }
            }
            else -> {
                caption(period.ifBlank { string(R.string.dashboard_coverage) })
                val known = real.filter { it.followersKnown }
                if (known.size >= 2) {
                    bars(known.zipWithNext().map { (a,b) -> (b.followers - a.followers).toDouble() }, known.drop(1).map { it.dayLabel })
                    row(string(R.string.dashboard_net_growth), TwidgetStore.signedNumber(known.last().followers - known.first().followers))
                } else caption(string(R.string.dashboard_no_comparison))
            }
        }
        // The last evidence row shares the card's bottom inset. Weighted plots
        // consume the remaining height rather than leaving a fixed-height gap.
        (panel.getChildAt(panel.childCount - 1)?.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = 0
        root.contentDescription = listOfNotNull(root.contentDescription?.toString(), descriptions.joinToString(". ")).joinToString(". ")
    }

    /** Both counts use the same scale, so a 5.8× ratio reads visually too. */
    private class ComparisonBar(context: Context, private val fraction: Float, private val accent: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fill: android.graphics.Shader? = null
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            fill = android.graphics.LinearGradient(if (rtl) w.toFloat() else 0f, 0f,
                if (rtl) 0f else w.toFloat(), 0f, accent, 0xff85a3de.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = context.getColor(R.color.oneui_text_secondary); paint.alpha = 35
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            paint.color = accent; paint.alpha = 255; paint.shader = fill
            val length = maxOf(height.toFloat(), width * fraction.coerceIn(0f, 1f)).coerceAtMost(width.toFloat())
            if (fraction > 0) {
                val left = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) width - length else 0f
                canvas.drawRoundRect(left, 0f, left + length, height.toFloat(), radius, radius, paint)
            }
            paint.shader = null
        }
    }

    private class MiniBars(context: Context, private val values: List<Double?>, private val accent: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fill: android.graphics.Shader? = null
        private var negativeFill: android.graphics.Shader? = null
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val red = context.getColor(R.color.metric_red)
            negativeFill = android.graphics.LinearGradient(0f, 0f, 0f, h.coerceAtLeast(1).toFloat(),
                red, androidx.core.graphics.ColorUtils.blendARGB(red, android.graphics.Color.WHITE, .35f), android.graphics.Shader.TileMode.CLAMP)
            fill = android.graphics.LinearGradient(0f, 0f, 0f, h.coerceAtLeast(1).toFloat(),
                accent, 0xff85a3de.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            val step = width.toFloat() / values.size.coerceAtLeast(1)
            val maximum = values.filterNotNull().maxOfOrNull { abs(it) }?.coerceAtLeast(1.0) ?: 1.0
            val diverging = values.any { (it ?: 0.0) < 0 }
            val base = if (diverging) height / 2f else height.toFloat()
            val range = if (diverging) height / 2f else height.toFloat()
            val barWidth = minOf(step * .54f, 18f * resources.displayMetrics.density)
            val radius = barWidth / 2f
            values.forEachIndexed { index, value ->
                val left = step * index + (step - barWidth) / 2f
                val right = left + barWidth
                paint.color = context.getColor(R.color.oneui_divider); paint.alpha = 105; paint.shader = null
                canvas.drawRoundRect(left, 0f, right, height.toFloat(), radius, radius, paint)
                if (value != null && value != 0.0) {
                    paint.color = if (value < 0) context.getColor(R.color.metric_red) else accent; paint.alpha = 255
                    paint.shader = if (value < 0) negativeFill else fill
                    val end = base - (value / maximum * range).toFloat()
                    canvas.drawRoundRect(left, minOf(base, end), right, maxOf(base, end), radius, radius, paint)
                }
            }
            paint.shader = null
        }
    }
}
