package com.tjg.twidget.ui

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.tjg.twidget.R
import kotlin.math.roundToInt

/** Content priorities depend on each card's real width, including live resize frames. */
internal object DashboardCardDensity {
    fun apply(root: View, widthPx: Int) {
        if (widthPx <= 0) return
        val density = root.resources.displayMetrics.density
        val widthDp = widthPx / density
        fun dp(value: Float) = (value * density).roundToInt()

        root.findViewById<View>(R.id.dashboard_stat_header)?.let { header ->
            val inset = dp(12f)
            root.setPadding(inset, inset, inset, inset)
            header.minimumHeight = dp(24f)
            val label = root.findViewById<TextView>(R.id.metric_label)
            TextViewCompat.setAutoSizeTextTypeWithDefaults(label, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            TwidgetFonts.dashboardHeading(label)
            val streak = root.getTag(R.id.dashboard_stat_streak) == true
            root.findViewById<ImageView>(R.id.metric_platform_icon).apply {
                visibility = if (streak && widthDp >= 170f) View.VISIBLE else View.GONE
                layoutParams.width = dp(16f)
                layoutParams.height = dp(16f)
                (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8f)
            }
            val ordinary = ((widthDp - 144f) / 48f).coerceIn(0f, 1f)
            val expanded = ((widthDp - 220f) / 140f).coerceIn(0f, 1f)
            val value = root.findViewById<CardValueTextView>(R.id.followers_value)
            value.applyDesignScale(1f)
            value.configure(if (streak) 64f else 48f + 8f * ordinary + 8f * expanded,
                (100f + 10f * expanded).roundToInt(), minimumWidth = 85)
            root.findViewById<TextView>(R.id.stat_detail).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams.height = maxOf(dp(20f), paint.fontMetricsInt.bottom - paint.fontMetricsInt.top)
                val available = widthPx - root.paddingLeft - root.paddingRight
                visibility = if (text.isNotBlank() && paint.measureText(text.toString()) <= available)
                    View.VISIBLE else View.INVISIBLE
            }
        }

        root.findViewById<View>(R.id.dashboard_post_heading)?.let { label ->
            val inset = dp(12f)
            root.setPadding(inset, inset, inset, inset)
            (label.parent as View).minimumHeight = dp(24f)
            (label.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = if (root.findViewById<View>(R.id.dashboard_view_report) != null) dp(8f) else 0
            }
            root.findViewById<View>(R.id.dashboard_post_divider)?.let { divider ->
                (divider.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    height = dp(1f)
                    topMargin = dp(8f)
                    bottomMargin = dp(8f)
                }
            }
            root.findViewById<View>(R.id.dashboard_view_report)?.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            root.findViewById<View>(R.id.dashboard_post_body)?.setPadding(0, 0, 0, 0)
            root.findViewById<DashboardPostMediaLayout>(R.id.dashboard_post_media)?.let { media ->
                val gap = dp(16f)
                (media.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    width = media.preferredWidth
                    marginStart = gap
                    // Card design reapplies margins during measurement. Resolve
                    // the relative gutter before LinearLayout measures the text.
                    resolveLayoutDirection(root.layoutDirection)
                }
                val copyWidth = widthPx - inset * 2 - media.preferredWidth - gap
                media.visibility = if (copyWidth >= dp(208f)) View.VISIBLE else View.GONE
            }
        }
    }
}
