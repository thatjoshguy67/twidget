package com.tjg.twidget.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.tjg.twidget.R
import com.tjg.twidget.data.AccountAverageSeries
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.HistoryRangePolicy
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.MetricChartView
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.text.NumberFormat
import java.util.Locale

class MetricChartActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private lateinit var metricId: String
    private lateinit var chartView: MetricChartView
    private lateinit var valueView: TextView
    private lateinit var deltaView: TextView
    private lateinit var subtitleView: TextView
    private var fullHistory = emptyList<HistorySample>()
    private var selectedRange = HistoryRange.WEEK
    private var rangeChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_metric_chart)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
        metricId = intent.getStringExtra(EXTRA_METRIC_ID).orEmpty()
        if (username.isBlank() || metricId.isBlank()) {
            finish()
            return
        }

        val root = findViewById<ToolbarLayout>(R.id.metric_chart_root)
        root.setTitle(metricTitle(metricId))
        root.setNavigationButtonOnClickListener { finishWithResult() }
        applyEdgeToEdgeInsets(root)

        chartView = findViewById(R.id.metric_chart_view)
        valueView = findViewById(R.id.metric_chart_value)
        deltaView = findViewById(R.id.metric_chart_delta)
        subtitleView = findViewById(R.id.metric_chart_subtitle)

        fullHistory = TwidgetStore.fullHistory(this, username)
        selectedRange = TwidgetStore.chartRange(this, username, metricId)
        buildRangeChips()
        renderChart()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithResult()
    }

    private fun finishWithResult() {
        if (rangeChanged) setResult(RESULT_OK)
        finish()
    }

    private fun buildRangeChips() {
        val row = findViewById<LinearLayout>(R.id.metric_chart_range_row)
        row.removeAllViews()
        HistoryRange.entries.forEach { range ->
            val available = HistoryRangePolicy.isAvailable(range, fullHistory)
            row.addView(createRangeChip(range, available))
        }
    }

    private fun createRangeChip(range: HistoryRange, available: Boolean): AppCompatButton =
        AppCompatButton(this).apply {
            text = getString(range.labelRes)
            isAllCaps = false
            isEnabled = available
            alpha = if (available) 1f else 0.45f
            typeface = Typeface.create("sec", Typeface.BOLD)
            setTextColor(getColor(if (range == selectedRange) android.R.color.white else R.color.oneui_text_primary))
            background = chipBackground(range == selectedRange)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                if (!available) {
                    Toast.makeText(
                        this@MetricChartActivity,
                        getString(
                            R.string.chart_range_locked,
                            range.requiredDays,
                            getString(range.labelRes),
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                if (range == selectedRange) return@setOnClickListener
                selectedRange = range
                TwidgetStore.saveChartRange(this@MetricChartActivity, username, metricId, range)
                rangeChanged = true
                buildRangeChips()
                renderChart()
            }
        }

    private fun chipBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(
                ContextCompat.getColor(
                    this@MetricChartActivity,
                    if (selected) R.color.oneui_accent else R.color.oneui_card_bg,
                ),
            )
            if (!selected) {
                setStroke(dp(1), ContextCompat.getColor(this@MetricChartActivity, R.color.oneui_divider))
            }
        }

    private fun renderChart() {
        val binding = metricBinding(metricId)
        val stats = TwidgetStore.currentStats(this, username)
        val chartHistory = TwidgetStore.chartHistory(this, username, selectedRange)
        val rangedHistory = TwidgetStore.rangedHistory(this, username, selectedRange)
        val visible = chartHistory.filter(binding.known)
        val knownFull = fullHistory.filter(binding.known)

        valueView.text = binding.currentValue(stats)
        valueView.typeface = heavyTypeface

        val delta = rangeDelta(rangedHistory.filter(binding.known), binding.selector)
        deltaView.text = if (delta == 0L) "" else TwidgetStore.signedNumber(delta)
        deltaView.setTextColor(getColor(if (delta < 0) R.color.metric_red else R.color.metric_green))
        deltaView.visibility = if (delta == 0L) View.GONE else View.VISIBLE

        subtitleView.text = getString(
            R.string.chart_history_subtitle,
            getString(selectedRange.labelRes),
            HistoryRangePolicy.historySpanDays(fullHistory),
        )

        chartView.setData(visible, binding.selector)
        chartView.setAverageSeries(
            AccountAverageSeries.values(
                knownFull,
                visible,
                binding.selector,
                allowSparseHistory = metricId == DashboardCardType.FOLLOWERS.id &&
                    knownFull.any { it.imported && it.followersKnown },
            ),
        )
    }

    private fun rangeDelta(history: List<HistorySample>, selector: (HistorySample) -> Long): Long {
        if (history.size < 2) return 0L
        return selector(history.last()) - selector(history.first())
    }

    private fun metricTitle(metricId: String): String = when (metricId) {
        DashboardCardType.FOLLOWERS.id -> getString(R.string.followers)
        DashboardCardType.FOLLOWING.id -> getString(R.string.following)
        DashboardCardType.POSTS.id -> getString(R.string.posts)
        DashboardCardType.LIKES.id -> getString(R.string.likes)
        else -> getString(R.string.chart_history_title)
    }

    private fun metricBinding(metricId: String): MetricChartBinding {
        val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
        return when (metricId) {
            DashboardCardType.FOLLOWERS.id -> MetricChartBinding(
                known = { it.followersKnown },
                selector = { it.followers },
                currentValue = { stats ->
                    if (stats.followersKnown) numberFormat.format(stats.followersCount) else "--"
                },
            )
            DashboardCardType.FOLLOWING.id -> MetricChartBinding(
                known = { it.followingKnown },
                selector = { it.following },
                currentValue = { stats ->
                    if (stats.followingKnown) numberFormat.format(stats.followingsCount) else "--"
                },
            )
            DashboardCardType.POSTS.id -> MetricChartBinding(
                known = { it.postsKnown },
                selector = { it.posts },
                currentValue = { stats ->
                    if (stats.postsKnown) numberFormat.format(stats.statusesCount) else "--"
                },
            )
            DashboardCardType.LIKES.id -> MetricChartBinding(
                known = { it.likesKnown },
                selector = { it.likes },
                currentValue = { stats ->
                    if (stats.likesKnown) numberFormat.format(stats.likeCount) else "--"
                },
            )
            else -> error("Unsupported metric chart: $metricId")
        }
    }

    private val heavyTypeface: Typeface by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.create("sec", Typeface.NORMAL), 700, false)
        } else {
            Typeface.create("sec", Typeface.BOLD)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_METRIC_ID = "metric_id"

        fun intent(context: Context, username: String, metricId: String): Intent =
            Intent(context, MetricChartActivity::class.java)
                .putExtra(EXTRA_USERNAME, username)
                .putExtra(EXTRA_METRIC_ID, metricId)
    }
}

private data class MetricChartBinding(
    val known: (HistorySample) -> Boolean,
    val selector: (HistorySample) -> Long,
    val currentValue: (ProfileStats) -> String,
)
