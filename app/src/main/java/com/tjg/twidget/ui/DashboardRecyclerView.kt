package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.graphics.Rect
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Native SESL scrolling and item gestures; the gutter enlarges only resize grips. */
class DashboardRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = 0) : RecyclerView(context, attrs, defStyleAttr) {
    init {
        // XML chrome is inflated with this RecyclerView as its parent before
        // the account adapter attaches, so native LayoutParams must already exist.
        layoutManager = GridLayoutManager(context, 2)
        clipChildren = false
        clipToPadding = false
    }
    private var resizeTouchTarget: View? = null
    var onGestureCancelled: (() -> Unit)? = null
    var columnCount: Int
        get() = (layoutManager as? GridLayoutManager)?.spanCount ?: 2
        set(value) { (layoutManager as? GridLayoutManager)?.spanCount = value.coerceAtLeast(1) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) onGestureCancelled?.invoke()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resizeTouchTarget = null
            val extra = (12f * resources.displayMetrics.density).toInt()
            var nearest = Float.POSITIVE_INFINITY
            for (index in 0 until childCount) {
                val card = getChildAt(index) as? ViewGroup ?: continue
                val buttons = (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<android.widget.ImageButton>()
                if (buttons.any { button -> val rect = Rect(); button.getGlobalVisibleRect(rect) && rect.contains(event.rawX.toInt(), event.rawY.toInt()) }) {
                    resizeTouchTarget = null
                    return super.dispatchTouchEvent(event)
                }
                val handles = (0 until card.childCount).map { card.getChildAt(it) }
                    .filter { (it.tag as? String)?.startsWith("dashboard_resize_") == true }
                for (handle in handles) {
                    val bounds = Rect()
                    if (!handle.getGlobalVisibleRect(bounds)) continue
                    val edgeX = if (handle.getTag(com.tjg.twidget.R.id.dashboard_resize_left_edge) == true) bounds.left else bounds.right
                    val dx = event.rawX - edgeX
                    val dy = maxOf(bounds.top - event.rawY, 0f, event.rawY - bounds.bottom)
                    val distance = dx * dx + dy * dy
                    // Grow into the surrounding gutter, preserving a clear centre
                    // pickup target between both grips on four-column cards.
                    if (handle.getTag(com.tjg.twidget.R.id.dashboard_resize_left_edge) == true) bounds.left -= extra
                    else bounds.right += extra
                    bounds.top -= extra; bounds.bottom += extra
                    if (bounds.contains(event.rawX.toInt(), event.rawY.toInt()) && distance < nearest) {
                        resizeTouchTarget = handle
                        nearest = distance
                    }
                }
            }
        }
        resizeTouchTarget?.let { handle ->
            val origin = IntArray(2).also(handle::getLocationOnScreen)
            val forwarded = MotionEvent.obtain(event)
            forwarded.offsetLocation(event.rawX - origin[0] - forwarded.x, event.rawY - origin[1] - forwarded.y)
            handle.dispatchTouchEvent(forwarded)
            forwarded.recycle()
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                resizeTouchTarget = null
            return true
        }
        return super.dispatchTouchEvent(event)
    }


    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = (1200 * resources.displayMetrics.density).toInt()
        val width = if (MeasureSpec.getSize(widthMeasureSpec) > limit)
            MeasureSpec.makeMeasureSpec(limit, MeasureSpec.EXACTLY) else widthMeasureSpec
        super.onMeasure(width, heightMeasureSpec)
    }
}
