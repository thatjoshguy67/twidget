package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.tjg.twidget.R

/** Keeps the optional widget artwork only when the native text has breathing room. */
class BriefDashboardCardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val icon = findViewById<View>(R.id.brief_dashboard_icon)
        val copy = findViewById<View>(R.id.brief_dashboard_copy)
        if (icon != null && copy != null && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val width = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            // Measure the real fonts and wrapped lines before constraining the paragraph
            // to the remaining space. This also responds to language and font-scale changes.
            copy.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val artwork = icon.layoutParams as LayoutParams
            val available = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom
            val breathingRoom = (24f * resources.displayMetrics.density).toInt()
            val required = copy.measuredHeight + artwork.height + artwork.topMargin + artwork.bottomMargin + breathingRoom
            icon.visibility = if (required <= available) View.VISIBLE else View.GONE
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
