package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.tjg.twidget.R
import kotlin.math.roundToInt

/** Mirrors the widget: state artwork above a bottom-aligned editorial title and description. */
class BriefDashboardCardLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0) : LinearLayout(context, attrs, defStyleAttr) {
    override fun onFinishInflate() {
        super.onFinishInflate()
        // Match the widget's explicit 700 / 400 hierarchy. Theme-provided
        // variable font metadata can otherwise flatten XML textStyle="bold".
        findViewById<TextView>(R.id.brief_dashboard_title).apply {
            typeface = TwidgetFonts.forApp(context, 700)
            paint.isFakeBoldText = false
        }
        TwidgetFonts.setRole(findViewById(R.id.brief_dashboard_message), TwidgetFonts.Role.SUMMARY)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val inset = dp(12)
        setPadding(inset, inset, inset, inset)
        val icon = findViewById<View>(R.id.brief_dashboard_icon)
        fun artwork(size: Int, gap: Int) {
            icon?.apply {
                visibility = View.VISIBLE
                layoutParams.width = dp(size)
                layoutParams.height = dp(size)
                (layoutParams as LayoutParams).bottomMargin = dp(gap)
            }
        }
        artwork(32, 8)
        val title = findViewById<TextView>(R.id.brief_dashboard_title)
        val body = findViewById<TextView>(R.id.brief_dashboard_message)
        title?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                if ((MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density).roundToInt() < 340) 22f else 24f)
            paint.isFakeBoldText = false
            maxLines = 3
        }
        body?.maxLines = 3
        if (title != null && body != null && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val width = MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) - inset * 2).coerceAtLeast(1), MeasureSpec.EXACTLY)
            fun intrinsic(text: TextView, lines: Int): Int {
                text.maxLines = lines
                text.measure(width, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                return text.measuredHeight
            }
            var available = (MeasureSpec.getSize(heightMeasureSpec) - inset * 2 - dp(32 + 8)).coerceAtLeast(0)
            var gap = dp(6)
            var titleLines = 3
            var titleHeight = intrinsic(title, titleLines)
            val bodyHeights = (1..3).associateWith { intrinsic(body, it) }
            val minimumBody = bodyHeights.getValue(1)
            val compactAvailable = (MeasureSpec.getSize(heightMeasureSpec) - inset * 2 - dp(28 + 4)).coerceAtLeast(0)
            // Measure the actual selected font and emphasis spans. Reserving two
            // nominal body line-heights used to force a perfectly usable title to one line.
            if (titleHeight + gap + minimumBody > available ||
                (bodyHeights.getValue(2) > minimumBody && titleHeight + gap + bodyHeights.getValue(2) > available &&
                    titleHeight + dp(4) + bodyHeights.getValue(2) <= compactAvailable)) {
                artwork(28, 4)
                available = compactAvailable
                gap = dp(4)
            }
            while (titleLines > 1 && titleHeight + gap + minimumBody > available) {
                titleHeight = intrinsic(title, --titleLines)
            }
            title.maxLines = titleLines
            val remaining = (available - titleHeight - gap).coerceAtLeast(0)
            body.maxLines = (3 downTo 1).firstOrNull { bodyHeights.getValue(it) <= remaining } ?: 1
            (body.layoutParams as LayoutParams).topMargin = gap
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
