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
        val detail = findViewById<View>(R.id.stat_detail)
        val panel = findViewById<View>(R.id.dashboard_stat_context)
        split = getTag(R.id.dashboard_stat_expanded) == true && available >= dp(240) && panel != null
        val wasVisible = panel?.visibility == View.VISIBLE
        panel?.visibility = if (split) View.VISIBLE else View.GONE
        if (split && !wasVisible && isAttachedToWindow) {
            panel?.alpha = 0f
            panel?.animate()?.alpha(1f)?.setDuration(160)?.start()
        }
        columnWidth = if (split) ((available - dp(16)) * .46f).roundToInt() else available
        fun measure(view: View, w: Int, h: Int, mode: Int = MeasureSpec.EXACTLY) =
            view.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), mode))
        measure(header, available, height, MeasureSpec.AT_MOST)
        headerHeight = maxOf(dp(24), header.measuredHeight)
        measure(detail, columnWidth, (height - paddingTop - paddingBottom - headerHeight).coerceAtLeast(0), MeasureSpec.AT_MOST)
        detailHeight = if (detail.visibility == View.GONE) 0 else maxOf(dp(20), detail.measuredHeight)
        val bodyHeight = (height - paddingTop - paddingBottom - headerHeight - dp(8)).coerceAtLeast(0)
        measure(value, columnWidth, (bodyHeight - detailHeight).coerceAtLeast(0))
        if (split && panel != null) measure(panel, available - columnWidth - dp(16), bodyHeight)
        setMeasuredDimension(width, height)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val header = findViewById<View>(R.id.dashboard_stat_header)
        val value = findViewById<View>(R.id.followers_value)
        val detail = findViewById<View>(R.id.stat_detail)
        val panel = findViewById<View>(R.id.dashboard_stat_context)
        header.layout(paddingLeft, paddingTop, width - paddingRight, paddingTop + headerHeight)
        val bodyTop = paddingTop + headerHeight + dp(8)
        val bottom = height - paddingBottom
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val valueLeft = if (split && rtl) width - paddingRight - columnWidth else paddingLeft
        value.layout(valueLeft, bodyTop, valueLeft + columnWidth, bottom - detailHeight)
        detail.layout(valueLeft, bottom - detailHeight, valueLeft + columnWidth, bottom)
        if (split && panel != null) {
            val x = if (rtl) paddingLeft else valueLeft + columnWidth + dp(16)
            panel.layout(x, bodyTop, x + panel.measuredWidth, bottom)
        }
    }
}
