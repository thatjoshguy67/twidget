package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.tjg.twidget.R
import kotlin.math.roundToInt

/** One shell for compact values and expanded value/context compositions. */
class DashboardStatCardLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private var split = false
    private var columnWidth = 0
    private var headerHeight = 0
    private var detailHeight = 0
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val header = findViewById<View>(R.id.dashboard_stat_header)
        val value = findViewById<View>(R.id.followers_value)
        val detail = findViewById<android.widget.TextView>(R.id.stat_detail)
        val panel = findViewById<View>(R.id.dashboard_stat_context)
        split = getTag(R.id.dashboard_stat_expanded) == true && available >= dp(240) && panel != null
        val alignment = if (split) android.view.Gravity.START else android.view.Gravity.CENTER_HORIZONTAL
        (header as? LinearLayout)?.gravity = alignment or android.view.Gravity.CENTER_VERTICAL
        (value as? android.widget.TextView)?.gravity = alignment or android.view.Gravity.CENTER_VERTICAL
        detail.gravity = alignment
        val wasVisible = panel?.visibility == View.VISIBLE
        panel?.visibility = if (split) View.VISIBLE else View.GONE
        if (split && !wasVisible && isAttachedToWindow) {
            panel?.alpha = 0f
            panel?.animate()?.alpha(1f)?.setDuration(160)?.start()
        }
        columnWidth = if (split) ((available - dp(10)) * .40f).roundToInt() else available
        fun measure(view: View, w: Int, h: Int, mode: Int = MeasureSpec.EXACTLY) =
            view.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), mode))
        (getTag(if (split) R.id.dashboard_stat_detail_expanded else R.id.dashboard_stat_detail_compact) as? CharSequence)?.let {
            if (detail.text != it) detail.text = it
            detail.visibility = if (it.isBlank()) View.GONE else View.VISIBLE
        }
        measure(header, columnWidth, height, MeasureSpec.AT_MOST)
        headerHeight = maxOf(dp(24), header.measuredHeight)
        measure(detail, columnWidth, (height - paddingTop - paddingBottom - headerHeight).coerceAtLeast(0), MeasureSpec.AT_MOST)
        detailHeight = if (detail.visibility == View.GONE) 0 else maxOf(dp(20), detail.measuredHeight)
        val bodyHeight = (height - paddingTop - paddingBottom - headerHeight - dp(if (split) 4 else 8)).coerceAtLeast(0)
        measure(value, columnWidth, (bodyHeight - detailHeight).coerceAtLeast(0))
        if (split && panel != null) measure(panel, available - columnWidth - dp(10), height - paddingTop - paddingBottom)
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val header = findViewById<View>(R.id.dashboard_stat_header)
        val value = findViewById<View>(R.id.followers_value)
        val detail = findViewById<View>(R.id.stat_detail)
        val panel = findViewById<View>(R.id.dashboard_stat_context)
        val bodyTop = paddingTop + headerHeight + dp(if (split) 4 else 8)
        val bottom = height - paddingBottom
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val valueLeft = if (split && rtl) width - paddingRight - columnWidth else paddingLeft
        header.layout(valueLeft, paddingTop, valueLeft + columnWidth, paddingTop + headerHeight)
        value.layout(valueLeft, bodyTop, valueLeft + columnWidth, bottom - detailHeight)
        detail.layout(valueLeft, bottom - detailHeight, valueLeft + columnWidth, bottom)
        if (split && panel != null) {
            val x = if (rtl) paddingLeft else valueLeft + columnWidth + dp(10)
            panel.layout(x, paddingTop, x + panel.measuredWidth, bottom)
        }
    }
}
