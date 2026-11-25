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

/** Engagement composition cards share the account-scoped data range. */
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
            DashboardCardType.ENGAGEMENT_MIX -> mix(activity, data, days)
            DashboardCardType.ORIGINAL_REPLIES -> split(activity, data, days)
            else -> error("Unsupported interaction card")
        }
    }

    internal fun mix(activity: MainActivity, data: InteractionData, days: Int): View {
        val root = surface(activity).apply { setTag(R.id.dashboard_list_card, false); setPadding(dp(activity, 31), dp(activity, 12), dp(activity, 32), dp(activity, 12)) }
        val mix = InteractionAggregation.mixSummary(data, days)
        val slices = mix.slices
        val comparable = slices.all { it.percent != null }
        val content = row(activity)
        content.addView(InteractionDonutView(activity).apply {
            setData(if (comparable) slices.map { it.count ?: 0L } else emptyList(),
                if (comparable) slices.mapNotNull { it.count }.sum() else null)
        }, LinearLayout.LayoutParams(0, dp(activity, 150), .40f).apply { gravity = Gravity.CENTER_VERTICAL; marginEnd = dp(activity, 49) })
        val legend = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(activity, 20)) }
        legend.addView(text(activity, activity.getString(R.string.interaction_mix_title), 14f, true, true).apply {
            TwidgetFonts.dashboardHeading(this)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 18) })
        val icons = listOf(R.drawable.interaction_likes_icon,
            R.drawable.interaction_reposts_icon,
            R.drawable.interaction_replies_icon,
            R.drawable.interaction_quotes_icon)
        slices.forEachIndexed { index, slice ->
            val name = activity.getString(metricLabel(slice.metric))
            val amount = count(activity, slice.count)
            val percent = percentage(activity, slice.percent)
            val line = row(activity).apply {
                addView(android.widget.ImageView(activity).apply {
                    setImageResource(icons[index])
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(activity, 24), dp(activity, 24)).apply { marginEnd = dp(activity, 10) })
                addWeighted(this, text(activity, name, 18f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(text(activity, amount, 18f, true).apply { maxLines = 1 },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(activity, 8) })
                contentDescription = activity.getString(R.string.interaction_slice_description, name, amount, percent)
            }
            legend.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        content.addView(legend, LinearLayout.LayoutParams(0, -1, .60f))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val caption = mix.sampledPosts?.let { activity.getString(R.string.analytics_posts_replies_coverage, it) }
            ?: activity.getString(R.string.interaction_mix_unavailable).takeUnless { comparable }
        caption?.let { root.addView(text(activity, it, 11f, secondary = true)) }
        fun proportional(view: View) {
            if (view is TextView) view.setTag(R.id.dashboard_design_proportional_text,
                view.getTag(R.id.app_font_role) != TwidgetFonts.Role.DASHBOARD_HEADING)
            if (view is ViewGroup) for (i in 0 until view.childCount) proportional(view.getChildAt(i))
        }
        proportional(root)
        return root
    }

    internal fun split(activity: MainActivity, data: InteractionData, days: Int): View {
        val split = InteractionAggregation.split(data, days)
        val known = split.originals + split.replies > 0
        return comparison(activity, R.string.interaction_split_title, R.string.interaction_original,
            R.string.interaction_replies, if (known) split.originals.toLong() else null,
            if (known) split.replies.toLong() else null, activity.getString(rangeLabel(days)),
            activity.editModeController.cardSpan(DashboardCardType.ORIGINAL_REPLIES) > 1)
    }

    internal fun comparison(activity: MainActivity, title: Int, leftLabel: Int, rightLabel: Int,
        left: Long?, right: Long?, range: String?, wide: Boolean): View {
        val root = surface(activity).apply {
            setTag(R.id.dashboard_list_card, false)
            val inset = if (wide) 32 else 23
            setPadding(dp(activity, inset), dp(activity, 12), dp(activity, inset), dp(activity, 23))
        }
        root.addView(text(activity, activity.getString(title), 14f, true, true).apply {
            TwidgetFonts.dashboardHeading(this)
            gravity = Gravity.CENTER
        })
        root.addView(View(activity), LinearLayout.LayoutParams(-1, 0, 1f))
        val values = row(activity)
        fun value(amount: Long?) = text(activity, count(activity, amount), 40f, true).apply {
            setTag(R.id.dashboard_card_span, if (wide) 140 else 79)
            TwidgetFonts.setRole(this, TwidgetFonts.Role.INTERACTION_VALUE)
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(16, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        values.addView(value(left), LinearLayout.LayoutParams(0, dp(activity, 50), 1f))
        if (wide && range != null) values.addView(text(activity, range, 16f, true, true).apply { gravity = Gravity.CENTER })
        values.addView(value(right).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, dp(activity, 50), 1f))
        root.addView(values)
        root.addView(InteractionSplitView(activity).apply { setValues(left, right) },
            LinearLayout.LayoutParams(-1, dp(activity, 35)).apply { topMargin = dp(activity, 10) })
        val labels = row(activity)
        addWeighted(labels, text(activity, activity.getString(leftLabel), 16f).apply { maxLines = 1 })
        addWeighted(labels, text(activity, activity.getString(rightLabel), 16f).apply { gravity = Gravity.END; maxLines = 1 })
        root.addView(labels, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 10) })
        root.contentDescription = listOfNotNull(activity.getString(title), range,
            activity.getString(leftLabel) + ": " + count(activity,left), activity.getString(rightLabel) + ": " + count(activity,right)).joinToString(". ")
        fun proportional(view: View) {
            if (view is TextView) view.setTag(R.id.dashboard_design_proportional_text,
                view.getTag(R.id.app_font_role) != TwidgetFonts.Role.DASHBOARD_HEADING)
            if (view is ViewGroup) for (i in 0 until view.childCount) proportional(view.getChildAt(i))
        }
        proportional(root)
        return root
    }
}
