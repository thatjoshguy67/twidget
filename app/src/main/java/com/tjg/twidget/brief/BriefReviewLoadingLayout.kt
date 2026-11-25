package com.tjg.twidget.brief

import android.content.Context
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView

/** Keep the previous review below the initial viewport without making it inaccessible by scrolling. */
internal class BriefReviewLoadingLayout(context: Context) : LinearLayout(context) {
    private var startingScroll: Int? = null
    private val location = IntArray(2)
    private val viewportLocation = IntArray(2)
    private val listener = ViewTreeObserver.OnPreDrawListener {
        var ancestor = parent
        while (ancestor != null && ancestor !is NestedScrollView) ancestor = ancestor.parent
        val viewport = ancestor as? NestedScrollView
        if (viewport == null || viewport.height <= 0) true else {
            if (startingScroll == null) startingScroll = viewport.scrollY
            getLocationInWindow(location)
            viewport.getLocationInWindow(viewportLocation)
            val contentTop = location[1] - viewportLocation[1] + viewport.scrollY
            val target = maxOf((170 * resources.displayMetrics.density).toInt(),
                viewport.height - contentTop + requireNotNull(startingScroll) + (24 * resources.displayMetrics.density).toInt())
            if (minimumHeight == target) true else {
                minimumHeight = target
                // Remeasure before showing a frame with the older review exposed.
                false
            }
        }
    }
    init { orientation = VERTICAL }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(listener)
    }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(listener)
        super.onDetachedFromWindow()
    }
}
