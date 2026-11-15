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
        layoutManager = GridLayoutManager(context, 2)
        clipChildren = false
        clipToPadding = false
    }
    var onGestureCancelled: (() -> Unit)? = null
    var columnCount: Int
        get() = (layoutManager as? GridLayoutManager)?.spanCount ?: 2
        set(value) { (layoutManager as? GridLayoutManager)?.spanCount = value.coerceAtLeast(1) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) onGestureCancelled?.invoke()
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = (1200 * resources.displayMetrics.density).toInt()
        val width = if (MeasureSpec.getSize(widthMeasureSpec) > limit)
            MeasureSpec.makeMeasureSpec(limit, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(width, heightMeasureSpec)
    }
}
