package com.tjg.twidget.social

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.NestedScrollView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedChartPoint
import com.tjg.twidget.ui.MetricChartView
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Uses the dashboard's existing card surface and chart instead of a parallel visual system. */
class SocialDashboardView(context: Context) : NestedScrollView(context) {
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(96)) }
    init { isFillViewport = true; addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    fun bind(catalog: SocialCatalog, profile: SocialProfile, observations: List<MetricObservation>) {
        content.removeAllViews()
        card().apply {
            label(context.getString(R.string.brief_title), large = true)
            isClickable = true; isFocusable = true
            setOnClickListener { context.startActivity(Intent(context, ProfileBriefActivity::class.java).putExtra(ProfileBriefActivity.EXTRA_PROFILE, profile.id)) }
        }
        if (profile.linked) {
            val total = AudienceAggregation.total(profile, catalog.accountsById, observations, System.currentTimeMillis(), 24 * 60 * 60 * 1000L)
            card().apply {
                label(context.getString(R.string.social_all_audience))
                label(total.value?.let { (if (total.approximate) "≈ " else "") + NumberFormat.getIntegerInstance().format(it) }
                    ?: context.getString(R.string.social_partial), large = true)
                label(context.getString(R.string.social_audience_note))
            }
        }
        profile.accountIds.map(catalog.accountsById::getValue).forEach { account ->
            card().apply {
                label("${account.platform.label} · @${account.handle}", large = true).apply {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(account.platform.icon(context), null, null, null)
                    compoundDrawablePadding = dp(12)
                    contentDescription = text
                    setOnClickListener { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(account.platform.profileUrl(account)))) }
                }
                val samples = observations.filter { it.accountId == account.id && !it.estimated }
                val latest = samples.groupBy { it.metric }.mapValues { it.value.maxBy { sample -> sample.observedAt } }
                latest.values.forEach { value ->
                    label("${context.getString(value.metric.labelRes)}  ${value.displayValue(context)}", large = true)
                }
                val recent = samples.filter { it.metric == account.platform.audienceMetric && it.value != null && it.observedAt >= System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L }
                    .groupBy { it.observedAt / (24 * 60 * 60 * 1000L) }.values.map { it.maxBy(MetricObservation::observedAt) }.sortedBy { it.observedAt }
                if (recent.size >= 2) addView(MetricChartView(context).apply {
                    setSeries(recent.map { ImportedChartPoint(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it.observedAt)), requireNotNull(it.value)) })
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(190)))
                val last = samples.maxOfOrNull { it.observedAt }
                label(last?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                    ?: context.getString(R.string.social_unavailable))
            }
        }
    }
    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)); setBackgroundResource(R.drawable.metric_card_bg)
        content.addView(this, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
    }
    private fun LinearLayout.label(value: String, large: Boolean = false): TextView = AppCompatTextView(context).apply {
        text = value
        setTextAppearance(if (large) androidx.appcompat.R.style.TextAppearance_AppCompat_Title else androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
        setTextColor(context.getColor(if (large) R.color.oneui_text_primary else R.color.oneui_text_secondary))
        setPadding(0, dp(6), 0, dp(6))
        addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
