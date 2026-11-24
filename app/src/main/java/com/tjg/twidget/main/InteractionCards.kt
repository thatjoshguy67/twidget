package com.tjg.twidget.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.InteractionAggregation
import com.tjg.twidget.analytics.InteractionData
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.InteractionMetric
import com.tjg.twidget.analytics.InteractionStore
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.TwidgetFonts
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The four cards share a range, while each account keeps its own selection. */
internal object InteractionCards {
    private val graphMetrics = listOf(InteractionMetric.LIKES, InteractionMetric.REPOSTS, InteractionMetric.REPLIES, InteractionMetric.SHARES)
    private val allMetrics = InteractionMetric.entries.toList()
    private const val PREFS = "twidget_interaction_ui"
    private fun key(account: String, field: String) = "${account.trim().trimStart('@').lowercase(Locale.US)}:$field"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun count(context: Context, value: Long?): String = value?.let { NumberFormat.getIntegerInstance(AppLocales.applicationLocale()).format(it) } ?: context.getString(R.string.interaction_missing)
    private fun percentage(context: Context, value: Int?): String = value?.let { context.getString(R.string.interaction_percent, it) }
        ?: context.getString(R.string.interaction_missing)
    private fun metricLabel(metric: InteractionMetric): Int = when (metric) {
        InteractionMetric.LIKES -> R.string.interaction_likes
        InteractionMetric.REPOSTS -> R.string.interaction_reposts
        InteractionMetric.REPLIES -> R.string.interaction_replies
        InteractionMetric.SHARES -> R.string.interaction_shares
        InteractionMetric.QUOTES -> R.string.interaction_quotes
        InteractionMetric.BOOKMARKS -> R.string.interaction_bookmarks
        InteractionMetric.VIEWS -> R.string.interaction_views
    }
    private fun metricColor(metric: InteractionMetric): Int = when (metric) {
        InteractionMetric.LIKES -> R.color.interaction_likes_color
        InteractionMetric.REPOSTS -> R.color.interaction_reposts_color
        InteractionMetric.REPLIES -> R.color.interaction_replies_color
        InteractionMetric.QUOTES -> R.color.interaction_quotes_color
        else -> R.color.oneui_text_secondary
    }
    private fun rangeLabel(days: Int) = when (days) {
        30 -> R.string.interaction_last_30
        90 -> R.string.interaction_last_90
        else -> R.string.interaction_last_7
    }
    private fun text(context: Context, value: CharSequence, size: Float, bold: Boolean = false, secondary: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
        setTextColor(context.getColor(if (secondary) R.color.oneui_text_secondary else R.color.oneui_text_primary))
        typeface = TwidgetFonts.forApp(context, if (bold) 700 else 400)
    }
    private fun surface(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
        setBackgroundResource(R.drawable.metric_card_bg)
        setTag(R.id.dashboard_list_card, true)
    }
    private fun heading(context: Context, title: Int) = text(context, context.getString(title), 17f, true)
    private fun pill(context: Context, label: String, selected: Boolean, onClick: () -> Unit): TextView = text(context, label, 12f, selected).apply {
        gravity = Gravity.CENTER
        minimumHeight = dp(context, 40)
        setPadding(dp(context, 8), 0, dp(context, 8), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 24).toFloat()
            setColor(context.getColor(if (selected) R.color.interaction_chip_selected else R.color.interaction_chip_background))
        }
        isSelected = selected
        setOnClickListener { onClick() }
    }
    private fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun addWeighted(row: LinearLayout, view: View) = row.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

    fun create(activity: MainActivity, card: DashboardCardType, account: String): View {
        val pref = prefs(activity)
        val days = pref.getInt(key(account, "range"), 7).takeIf { it in listOf(7, 30, 90) } ?: 7
        val metric = runCatching { InteractionMetric.valueOf(pref.getString(key(account, "metric"), "LIKES") ?: "LIKES") }
            .getOrDefault(InteractionMetric.LIKES).takeIf { it in graphMetrics } ?: InteractionMetric.LIKES
        // Existing imports predate the interaction store. Hydrate only when no
        // daily history exists, so a rebind never overwrites a newer sync.
        var data = InteractionStore.all(activity, account)
        if (data.daily.isEmpty() && ImportedAnalyticsStore.all(activity, account).isNotEmpty()) {
            InteractionStore.seedCsv(activity, account)
            data = InteractionStore.all(activity, account)
        }
        return when (card) {
            DashboardCardType.INTERACTIONS -> graph(activity, account, data, days, metric)
            DashboardCardType.INTERACTION_TOTALS -> totals(activity, data, days)
            DashboardCardType.ENGAGEMENT_MIX -> mix(activity, data, days)
            DashboardCardType.ORIGINAL_REPLIES -> split(activity, data, days)
            else -> error("Unsupported interaction card")
        }
    }

    private fun graph(activity: MainActivity, account: String, data: InteractionData, days: Int, metric: InteractionMetric): View {
        val root = surface(activity)
        val header = row(activity)
        addWeighted(header, heading(activity, R.string.interactions_title))
        listOf(7 to R.string.interaction_range_7, 30 to R.string.interaction_range_30, 90 to R.string.interaction_range_90).forEach { (value, label) ->
            header.addView(pill(activity, activity.getString(label), days == value) {
                prefs(activity).edit().putInt(key(account, "range"), value).apply()
                activity.render(refreshRemote = false)
            })
        }
        root.addView(header)
        val chips = row(activity)
        graphMetrics.forEach { item -> addWeighted(chips, pill(activity, activity.getString(metricLabel(item)), item == metric) {
            prefs(activity).edit().putString(key(account, "metric"), item.name).apply()
            activity.render(refreshRemote = false)
        }) }
        root.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 6) })
        val points = InteractionAggregation.series(data, metric, days)
        val total = InteractionAggregation.total(points)
        root.addView(text(activity, count(activity, total), 27f, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 5) })
        root.addView(text(activity, activity.getString(R.string.interaction_range_description,
            activity.getString(metricLabel(metric)), activity.getString(rangeLabel(days))), 12f, secondary = true))
        if (points.all { it.value == null }) {
            root.addView(text(activity, activity.getString(R.string.interaction_empty), 13f, secondary = true).apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val formatter = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(AppLocales.applicationLocale(), "MMMd"), AppLocales.applicationLocale())
            root.addView(MetricChartView(activity).apply {
                compactCardInsets = true
                zeroBasedScale = true
                setOptionalSeries(points.map { it.date.format(formatter) to it.value })
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(activity, 4) })
        }
        root.addView(text(activity, activity.getString(R.string.interaction_by_post_date), 10f, secondary = true))
        return root
    }

    private fun totals(activity: MainActivity, data: InteractionData, days: Int): View {
        val root = surface(activity)
        val header = row(activity)
        addWeighted(header, heading(activity, R.string.interaction_stats_title))
        header.addView(text(activity, activity.getString(R.string.compare_title), 14f, true).apply {
            setTextColor(activity.getColor(R.color.interaction_replies_color))
            contentDescription = activity.getString(R.string.compare_title)
            minimumHeight = dp(activity, 40)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            setOnClickListener { CompareSheet(activity).show() }
        })
        root.addView(header)
        root.addView(text(activity, activity.getString(rangeLabel(days)), 12f, secondary = true))
        val totals = InteractionAggregation.totals(data, days)
        allMetrics.chunked(4).forEach { metrics ->
            val group = row(activity)
            metrics.forEach { metric ->
                val value = count(activity, totals[metric])
                val name = activity.getString(metricLabel(metric))
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(activity, value, 18f, true).apply {
                        maxLines = 1
                        setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                    }, LinearLayout.LayoutParams(-1, dp(activity, 26)))
                    addView(text(activity, name, 12f, secondary = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    contentDescription = activity.getString(R.string.interaction_value_description, name, value)
                }
                addWeighted(group, cell)
            }
            repeat(4 - metrics.size) { addWeighted(group, View(activity)) }
            root.addView(group, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return root
    }

    private fun mix(activity: MainActivity, data: InteractionData, days: Int): View {
        val root = surface(activity)
        root.addView(heading(activity, R.string.interaction_mix_title))
        root.addView(text(activity, activity.getString(rangeLabel(days)), 12f, secondary = true))
        val slices = InteractionAggregation.mix(data, days)
        val comparable = slices.all { it.percent != null }
        val content = row(activity)
        val donut = InteractionDonutView(activity)
        donut.setData(if (comparable) slices.map { it.count ?: 0L } else emptyList(),
            if (comparable) slices.mapNotNull { it.count }.sum() else null)
        content.addView(donut, LinearLayout.LayoutParams(0, -1, 1f))
        val legend = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        slices.forEach { slice ->
            val name = activity.getString(metricLabel(slice.metric))
            val amount = count(activity, slice.count)
            val percent = percentage(activity, slice.percent)
            val line = row(activity).apply {
                val dot = View(activity).apply { background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(activity.getColor(metricColor(slice.metric)))
                } }
                addView(dot, LinearLayout.LayoutParams(dp(activity, 9), dp(activity, 9)).apply { marginEnd = dp(activity, 7) })
                addWeighted(this, text(activity, name, 12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(activity, amount, 12f, true))
                addView(text(activity, percent, 12f, secondary = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 7) })
                minimumHeight = dp(activity, 31)
                contentDescription = activity.getString(R.string.interaction_slice_description, name, amount, percent)
            }
            legend.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(legend, LinearLayout.LayoutParams(0, -1, 1.35f))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!comparable) root.addView(text(activity, activity.getString(R.string.interaction_mix_unavailable), 11f, secondary = true))
        return root
    }

    private fun split(activity: MainActivity, data: InteractionData, days: Int): View {
        val root = surface(activity)
        root.addView(heading(activity, R.string.interaction_split_title))
        val split = InteractionAggregation.split(data, days)
        val hasPosts = split.originals + split.replies > 0
        val values = row(activity)
        listOf(Triple(R.string.interaction_original, split.originals, split.originalPercent), Triple(R.string.interaction_replies, split.replies, split.replyPercent)).forEachIndexed { index, (label, value, percent) ->
            val amount = if (hasPosts) activity.getString(R.string.interaction_split_value, count(activity, value.toLong()), percentage(activity, percent)) else activity.getString(R.string.interaction_missing)
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                if (index == 1) gravity = Gravity.END
                addView(text(activity, amount, 20f, true))
                addView(text(activity, activity.getString(label), 12f, secondary = true))
                contentDescription = activity.getString(R.string.interaction_value_description, activity.getString(label), amount)
            }
            addWeighted(values, cell)
        }
        root.addView(values, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(activity, 8) })
        root.addView(InteractionSplitView(activity).apply { setSplit(split.originals, split.replies) }, LinearLayout.LayoutParams(-1, dp(activity, 14)))
        val range = activity.getString(rangeLabel(days))
        val footer = if (hasPosts) range else activity.getString(R.string.interaction_range_description, range, activity.getString(R.string.interaction_split_empty))
        root.addView(text(activity, footer, 12f, secondary = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 7) })
        return root
    }
}
