package com.tjg.twidget.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Keep the visible preview at the content inset, allowing only the stack's real overhang. */
internal class DashboardPostMediaLayout(context: Context, private val stacked: Boolean) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    val preferredWidth: Int get() = dp(112 + if (stacked) 12 else 0)

    init { clipChildren = false; clipToPadding = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        val overhang = if (stacked) dp(6) else 0
        val thumbnailHeight = minOf(dp(88), (availableHeight - overhang * 2).coerceAtLeast(1))
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            (child.layoutParams as LayoutParams).apply {
                width = dp(112)
                height = thumbnailHeight
                gravity = Gravity.CENTER
            }
            child.translationX = if (stacked) dp(if (index == 0) -4 else 4).toFloat() else 0f
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
