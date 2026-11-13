package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.graphics.Rect
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.GridLayout

/** Keeps weighted card widths valid through the drawer's provisional launch measurements. */
class DashboardGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : GridLayout(context, attrs, defStyleAttr) {
    private var previousWidthSpec: Int? = null
    private var resizeBounds: Map<View, Rect>? = null
    private var resizeHeight = 0
    private var resizeTouchTarget: View? = null

    override fun setColumnCount(columnCount: Int) {
        if (columnCount > 0 && this.columnCount != columnCount) {
            // GridLayout writes automatic positions back to its specs. Release
            // those old indices before a Fold/window changes from four columns to two.
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                val params = child.layoutParams as LayoutParams
                val span = (child.getTag(com.tjg.twidget.R.id.dashboard_card_span) as? Int) ?: 2
                params.columnSpec = spec(UNDEFINED, span, 1f)
                params.rowSpec = spec(UNDEFINED)
                child.layoutParams = params
            }
        }
        super.setColumnCount(columnCount)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resizeTouchTarget = null
            val extra = (12f * resources.displayMetrics.density).toInt()
            for (index in 0 until childCount) {
                val card = getChildAt(index) as? ViewGroup ?: continue
                val handle = (0 until card.childCount).map { card.getChildAt(it) }
                    .firstOrNull { (it.tag as? String)?.startsWith("dashboard_resize_") == true } ?: continue
                val bounds = Rect()
                if (!handle.getGlobalVisibleRect(bounds)) continue
                bounds.inset(-extra, -extra)
                if (bounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                    resizeTouchTarget = handle
                    break
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

    /** Endpoint slots come from this GridLayout; live interpolation keeps every native child laid out. */
    fun setResizePreview(bounds: Map<View, Rect>, height: Int) {
        resizeBounds = bounds
        resizeHeight = height
        applyResizeBounds(bounds)
        requestLayout()
    }

    fun clearResizePreview() {
        resizeBounds = null
        requestLayout()
    }

    private fun applyResizeBounds(bounds: Map<View, Rect>) {
        bounds.forEach { (view, rect) ->
            view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
            view.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val preview = resizeBounds
        if (preview != null) applyResizeBounds(preview) else super.onLayout(changed, left, top, right, bottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        resizeBounds?.let { bounds ->
            bounds.forEach { (view, rect) ->
                view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(resizeHeight, heightMeasureSpec))
            return
        }
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        if (previousWidthSpec != widthMeasureSpec) {
            previousWidthSpec = widthMeasureSpec
            // GridLayout can discard its maximum-width constraint when the rail
            // first measures a narrow content pane. Reset its constraint graph
            // for the real width; requestLayout alone only resets cached values.
            columnCount = columnCount
        }
        // Resolve widths from the same column unit, including mixed one/two-column cards.
        // GridLayout weights alone distribute spare space rather than equal column widths.
        // Samsung Health keeps its native row height when four portrait columns
        // get narrower. Adapt each card's contents instead of squashing the row.
        val rowHeight = (160 * resources.displayMetrics.density).toInt()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as LayoutParams
            val span = (child.getTag(com.tjg.twidget.R.id.dashboard_card_span) as? Int) ?: 2
            params.width = (available * span / columnCount - params.leftMargin - params.rightMargin).coerceAtLeast(0)
            params.height = rowHeight.coerceAtLeast(1)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
