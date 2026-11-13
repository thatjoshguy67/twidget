package com.tjg.twidget.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Names take priority; a handle appears only when it can be read in full. */
internal class DashboardFollowerIdentityLayout(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val name = getChildAt(0) as? TextView
        val handle = getChildAt(1) as? TextView
        if (name != null && handle != null && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val margin = name.layoutParams as LayoutParams
            val required = name.paint.measureText(name.text.toString()) + margin.marginEnd +
                handle.paint.measureText(handle.text.toString())
            handle.visibility = if (required <= available) View.VISIBLE else View.GONE
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
