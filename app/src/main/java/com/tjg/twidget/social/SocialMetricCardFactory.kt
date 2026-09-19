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
        val root = LayoutInflater.from(context).inflate(R.layout.metric_card_followers, null, false) as android.widget.LinearLayout
        val total = AudienceAggregation.total(profile, catalog.accountsById, observations, System.currentTimeMillis(), DAY)
        root.tag = "combined_audience"
        root.findViewById<TextView>(R.id.followers_value).text = total.value?.let {
            (if (total.approximate) "≈ " else "") + NumberFormat.getIntegerInstance().format(it)
        } ?: context.getString(R.string.social_partial)
        root.findViewById<TextView>(R.id.metric_label).setText(R.string.social_all_audience)
        root.findViewById<View>(R.id.followers_delta).visibility = View.GONE
        root.findViewById<View>(R.id.followers_chart).visibility = View.GONE
        root.addView(TextView(context).apply {
            setText(R.string.social_audience_note)
            textSize = 12f
            setTextColor(context.getColor(R.color.oneui_text_secondary))
            val density = resources.displayMetrics.density
            setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (12 * density).toInt())
        })
        return root
    }

    fun metrics(platform: SocialPlatform): List<SocialMetric> = when (platform) {
        SocialPlatform.YOUTUBE -> listOf(SocialMetric.SUBSCRIBERS, SocialMetric.VIDEOS, SocialMetric.VIEWS)
        SocialPlatform.GITHUB -> listOf(SocialMetric.FOLLOWERS, SocialMetric.FOLLOWING, SocialMetric.REPOSITORIES, SocialMetric.STARS, SocialMetric.FORKS)
        SocialPlatform.X -> listOf(SocialMetric.FOLLOWERS, SocialMetric.FOLLOWING, SocialMetric.POSTS, SocialMetric.LIKES_GIVEN)
        else -> listOf(SocialMetric.FOLLOWERS, SocialMetric.FOLLOWING, SocialMetric.POSTS)
    }

    fun create(context: Context, account: PlatformAccount, metric: SocialMetric, observations: List<MetricObservation>): View {
        val root = LayoutInflater.from(context).inflate(R.layout.metric_card_followers, null, false)
        val now = System.currentTimeMillis()
        val samples = observations.filter { it.accountId == account.id && it.metric == metric && !it.estimated && it.observedAt <= now }
            .sortedBy { it.observedAt }
        val latest = samples.lastOrNull()
        root.tag = "metric:${account.id}:${metric.storageId}"
        root.findViewById<ImageView>(R.id.metric_platform_icon).setImageDrawable(account.platform.icon(context))
        root.findViewById<TextView>(R.id.metric_label).text = "${context.getString(metric.labelRes)} · ${account.platform.label}"
        root.findViewById<TextView>(R.id.followers_value).text = latest?.displayValue(context) ?: context.getString(R.string.social_unavailable)
        root.contentDescription = "${account.platform.label} · @${account.handle} · ${context.getString(metric.labelRes)}"
        val baseline = samples.lastOrNull { it.observedAt <= now - DAY && it.observedAt >= now - 2 * DAY }
        val delta = if (latest?.value != null && baseline?.value != null && now - latest.observedAt <= DAY &&
            latest.precision == MetricPrecision.EXACT && baseline.precision == MetricPrecision.EXACT) latest.value - baseline.value else null
        root.findViewById<TextView>(R.id.followers_delta).apply {
            visibility = if (delta == null) View.GONE else View.VISIBLE
            text = delta?.let { (if (it > 0) "+" else "") + NumberFormat.getIntegerInstance().format(it) }.orEmpty()
            setTextColor(context.getColor(if ((delta ?: 0) < 0) R.color.metric_red else R.color.metric_green))
        }
        val recent = samples.filter { it.value != null && it.observedAt >= now - 7 * DAY }
            .groupBy { it.observedAt / DAY }.values.map { it.last() }
        root.findViewById<MetricChartView>(R.id.followers_chart).setSeries(recent.map {
            ImportedChartPoint(java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(it.observedAt)), requireNotNull(it.value))
        })
        return root
    }

    private const val DAY = 24 * 60 * 60 * 1000L
}
