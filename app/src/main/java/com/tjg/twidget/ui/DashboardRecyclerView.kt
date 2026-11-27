package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Native SESL scrolling and item gestures. */
class DashboardRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0) : RecyclerView(context, attrs, defStyleAttr) {
    init {
        // XML chrome is inflated with this RecyclerView as its parent before
        // the account adapter attaches, so native LayoutParams must already exist.
        layoutManager = GridLayoutManager(context, DashboardLayoutPolicy.columns(
            resources.configuration.smallestScreenWidthDp, resources.configuration.screenWidthDp))
        clipChildren = false
        clipToPadding = false
    }
    internal var lastTouchY = Float.NaN
        private set
    var onGestureCancelled: (() -> Unit)? = null
    var columnCount: Int
        get() = (layoutManager as? GridLayoutManager)?.spanCount ?: 2
        set(value) { (layoutManager as? GridLayoutManager)?.spanCount = value.coerceAtLeast(1) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            lastTouchY = event.y
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) onGestureCancelled?.invoke()
        return super.dispatchTouchEvent(event)
    }

    override fun canScrollVertically(direction: Int): Boolean {
        if (direction < 0) {
            val manager = layoutManager
            // The zero-height notice header is skipped by GridLayoutManager's
            // scrollbar estimate, which otherwise reports an offset at the top.
            val first = manager?.findViewByPosition(0)
            if (first != null && manager.getDecoratedTop(first) >= paddingTop) return false
        }
        return super.canScrollVertically(direction)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = (DashboardLayoutPolicy.MAX_CONTENT_WIDTH_DP * resources.displayMetrics.density).toInt()
        val width = if (MeasureSpec.getSize(widthMeasureSpec) > limit)
            MeasureSpec.makeMeasureSpec(limit, MeasureSpec.EXACTLY) else widthMeasureSpec
        // Account for the rail, safe insets and multi-window bounds. A landscape
        // phone remains compact even when its long edge exceeds the tablet threshold.
        if (MeasureSpec.getMode(width) != MeasureSpec.UNSPECIFIED) {
            columnCount = DashboardLayoutPolicy.columns(resources.configuration.smallestScreenWidthDp,
                ((MeasureSpec.getSize(width) - paddingLeft - paddingRight) / resources.displayMetrics.density).toInt())
        }
        super.onMeasure(width, heightMeasureSpec)
    }
}
