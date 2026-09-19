package com.tjg.twidget.social

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedChartPoint
import com.tjg.twidget.ui.MetricChartView
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** Bind provider data into the same metric card used by the original dashboard. */
object SocialMetricCardFactory {
    fun audience(context: Context, catalog: SocialCatalog, profile: SocialProfile, observations: List<MetricObservation>): View {
        val root = LayoutInflater.from(context).inflate(R.layout.metric_card_audience, null, false)
        val total = AudienceAggregation.total(profile, catalog.accountsById, observations, System.currentTimeMillis(), DAY)
        root.tag = "combined_audience"
        root.findViewById<TextView>(R.id.followers_value).text = total.value?.let {
            (if (total.approximate) "≈ " else "") + NumberFormat.getIntegerInstance().format(it)
        } ?: context.getString(R.string.social_partial)
        if (total.value == null) root.findViewById<TextView>(R.id.followers_value).textSize = 16f
        root.findViewById<View>(R.id.audience_info).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(context).setTitle(R.string.social_all_audience)
                .setMessage(context.getString(R.string.social_audience_note) + "\n\n" + context.getString(R.string.social_audience_precision))
                .setPositiveButton(android.R.string.ok, null).show()
        }
        return root
    }

    fun metrics(platform: SocialPlatform): List<SocialMetric> = when (platform) {
        SocialPlatform.YOUTUBE -> listOf(SocialMetric.SUBSCRIBERS, SocialMetric.VIDEOS, SocialMetric.VIEWS)
        SocialPlatform.GITHUB -> listOf(SocialMetric.FOLLOWERS, SocialMetric.FOLLOWING, SocialMetric.REPOSITORIES, SocialMetric.STARS, SocialMetric.FORKS)
        SocialPlatform.X -> listOf(SocialMetric.FOLLOWERS, SocialMetric.FOLLOWING, SocialMetric.POSTS, SocialMetric.LIKES_GIVEN)
        else -> listOf(SocialMetric.FOLLOWERS, SocialMetric.FOLLOWING, SocialMetric.POSTS)
    }

    fun stat(context: Context, platform: SocialPlatform, label: String, value: String, detail: String): View =
        LayoutInflater.from(context).inflate(R.layout.metric_card_small_stat, null, false).apply {
            findViewById<ImageView>(R.id.metric_platform_icon).apply {
                setImageDrawable(platform.icon(context)); contentDescription = platform.label
            }
            findViewById<TextView>(R.id.metric_label).text = label
            findViewById<TextView>(R.id.followers_value).text = value
            findViewById<TextView>(R.id.stat_detail).apply {
                text = detail; visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
            }
        }

    fun smallStat(context: Context, account: PlatformAccount, metric: SocialMetric, observations: List<MetricObservation>): View {
        val now = System.currentTimeMillis()
        val samples = observations.filter { it.accountId == account.id && it.metric == metric && !it.estimated && it.observedAt <= now }
        val latest = samples.maxByOrNull { it.observedAt }
        val baseline = samples.filter { it.observedAt <= now - DAY && it.observedAt >= now - 2 * DAY }.maxByOrNull { it.observedAt }
        val delta = if (latest?.value != null && baseline?.value != null && now - latest.observedAt <= DAY &&
            latest.precision == MetricPrecision.EXACT && baseline.precision == MetricPrecision.EXACT) latest.value - baseline.value else null
        val detail = when {
            latest?.value == null -> context.getString(R.string.social_unavailable)
            now - latest.observedAt > DAY -> context.getString(R.string.social_updated_at,
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(latest.observedAt)))
            delta != null && delta != 0L -> context.getString(R.string.social_daily_change,
                (if (delta > 0) "+" else "") + NumberFormat.getIntegerInstance().format(delta))
            else -> ""
        }
        val value = latest?.value?.let {
            (if (latest.precision == MetricPrecision.ROUNDED) "≈ " else "") + com.tjg.twidget.data.TwidgetStore.compactNumber(it)
        } ?: "—"
        return stat(context, account.platform, context.getString(metric.labelRes), value, detail).apply {
            tag = "metric:${account.id}:${metric.storageId}"
            contentDescription = "${account.platform.label} · @${account.handle} · ${context.getString(metric.labelRes)}"
        }
    }

    fun create(context: Context, account: PlatformAccount, metric: SocialMetric, observations: List<MetricObservation>): View {
        val root = LayoutInflater.from(context).inflate(R.layout.metric_card_followers, null, false)
        val now = System.currentTimeMillis()
        val samples = observations.filter { it.accountId == account.id && it.metric == metric && !it.estimated && it.observedAt <= now }
            .sortedBy { it.observedAt }
        val latest = samples.lastOrNull()
        root.tag = "metric:${account.id}:${metric.storageId}"
        root.findViewById<ImageView>(R.id.metric_type_icon).setImageResource(metric.iconRes)
        root.findViewById<ImageView>(R.id.metric_platform_icon).apply {
            visibility = View.VISIBLE; setImageDrawable(account.platform.icon(context))
            contentDescription = account.platform.label
        }
        root.findViewById<TextView>(R.id.metric_label).text = context.getString(metric.labelRes)
        root.findViewById<TextView>(R.id.followers_value).text = latest?.displayValue(context) ?: context.getString(R.string.social_unavailable)
        root.contentDescription = "${account.platform.label} · @${account.handle} · ${context.getString(metric.labelRes)}"
        val baseline = samples.lastOrNull { it.observedAt <= now - DAY && it.observedAt >= now - 2 * DAY }
        val delta = if (latest?.value != null && baseline?.value != null && now - latest.observedAt <= DAY &&
            latest.precision == MetricPrecision.EXACT && baseline.precision == MetricPrecision.EXACT) latest.value - baseline.value else null
        root.findViewById<TextView>(R.id.followers_delta).apply {
            visibility = if (delta == null) View.GONE else View.VISIBLE
            text = delta?.let { (if (it > 0) "+" else "") + NumberFormat.getIntegerInstance().format(it) }.orEmpty()
            contentDescription = delta?.let { context.getString(R.string.social_daily_change, text) }
            setTextColor(context.getColor(if ((delta ?: 0) < 0) R.color.metric_red else R.color.metric_green))
        }
        val recent = samples.filter { it.value != null && it.observedAt >= now - 7 * DAY }
            .groupBy { it.observedAt / DAY }.values.map { it.last() }
        val status = when {
            latest == null || latest.value == null -> context.getString(R.string.social_unavailable)
            now - latest.observedAt > DAY -> context.getString(R.string.social_updated_at,
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(latest.observedAt)))
            recent.size < 2 -> context.getString(R.string.social_history_pending)
            delta != null -> context.getString(R.string.social_daily_change,
                (if (delta > 0) "+" else "") + NumberFormat.getIntegerInstance().format(delta))
            else -> null
        }
        root.findViewById<MetricChartView>(R.id.followers_chart).apply {
            visibility = if (recent.size < 2) View.GONE else View.VISIBLE
        }.setSeries(recent.map {
            ImportedChartPoint(java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it.observedAt)), requireNotNull(it.value))
        })
        if (status != null) (root as android.widget.LinearLayout).addView(TextView(context).apply {
            text = status; textSize = 12f
            setTextColor(context.getColor(R.color.oneui_text_secondary))
            val inset = (16 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset / 2, inset, inset)
            if (recent.size < 2) {
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
        })
        return root
    }

    private const val DAY = 24 * 60 * 60 * 1000L
}
