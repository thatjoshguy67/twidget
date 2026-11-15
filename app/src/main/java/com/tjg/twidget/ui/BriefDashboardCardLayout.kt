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
        findViewById<View>(R.id.brief_dashboard_icon)?.apply {
            visibility = View.VISIBLE
            layoutParams.width = dp(32)
            layoutParams.height = dp(32)
            (layoutParams as LayoutParams).bottomMargin = dp(8)
        }
        val title = findViewById<TextView>(R.id.brief_dashboard_title)
        val body = findViewById<TextView>(R.id.brief_dashboard_message)
        title?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                if ((MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density).roundToInt() < 340) 22f else 24f)
            paint.isFakeBoldText = false
        }
        if (title != null && body != null && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = (MeasureSpec.getSize(heightMeasureSpec) - inset * 2 - dp(32 + 8)).coerceAtLeast(0)
            val gap = dp(6)
            val minimumBodyLines = if (resources.configuration.fontScale >= 1.6f) 1 else 2
            title.maxLines = if (available - minimumBodyLines * body.lineHeight - gap >= title.lineHeight * 2) 2 else 1
            title.measure(MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthMeasureSpec) - inset * 2).coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            body.maxLines = ((available - title.measuredHeight - gap) / body.lineHeight.coerceAtLeast(1)).coerceIn(1, 3)
            (body.layoutParams as LayoutParams).topMargin = gap
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
