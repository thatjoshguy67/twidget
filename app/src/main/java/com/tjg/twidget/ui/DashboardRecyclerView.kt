package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
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
    internal var onAdditionalPointer: (() -> Unit)? = null
    private var primaryPointer = 0
    private var scrollPointer = -1
    private var scrollY = 0f
    private var scrollRemainder = 0f
    private var scrollStarted = false
    private var heldEvent: MotionEvent? = null
    internal var isHoldingCard = false
        private set
    internal val isScrollingHeldCard get() = scrollPointer != -1
    private var swallowRemainingPointers = false

    internal fun beginCardDrag() {
        isHoldingCard = true
        scrollPointer = -1
    }

    internal fun endCardDrag() {
        isHoldingCard = false
        scrollPointer = -1
        heldEvent?.recycle()
        heldEvent = null
    }

    /** Re-evaluate the landing slot after scrolling beneath a stationary finger. */
    internal fun refreshCardDrag() {
        if (isHoldingCard) heldEvent?.let { super.dispatchTouchEvent(it) }
    }
    var columnCount: Int
        get() = (layoutManager as? GridLayoutManager)?.spanCount ?: 2
        set(value) { (layoutManager as? GridLayoutManager)?.spanCount = value.coerceAtLeast(1) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowRemainingPointers = false
            primaryPointer = event.getPointerId(0)
        }
        if (swallowRemainingPointers) return true
        val primaryIndex = event.findPointerIndex(primaryPointer)
        if (primaryIndex >= 0) lastTouchY = event.getY(primaryIndex)
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) onGestureCancelled?.invoke()
        if (!isHoldingCard && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) onAdditionalPointer?.invoke()
        if (!isHoldingCard || primaryIndex < 0) {
            val handled = super.dispatchTouchEvent(event)
            // The card's hold recognizer can start dragging inside this dispatch.
            if (isHoldingCard && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)
                return dispatchTouchEvent(event)
            if (isHoldingCard && event.actionMasked == MotionEvent.ACTION_MOVE) {
                heldEvent?.recycle()
                heldEvent = MotionEvent.obtain(event)
                refreshCardDrag()
            }
            return handled
        }

        val lifted = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && scrollPointer == -1) {
            scrollPointer = event.getPointerId(event.actionIndex)
            scrollY = event.getY(event.actionIndex)
            scrollRemainder = 0f
            scrollStarted = false
        }
        val scrollIndex = event.findPointerIndex(scrollPointer)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && scrollIndex >= 0) {
            scrollRemainder += scrollY - event.getY(scrollIndex)
            scrollY = event.getY(scrollIndex)
            if (!scrollStarted) {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(scrollRemainder) > slop) {
                    scrollStarted = true
                    scrollRemainder -= kotlin.math.sign(scrollRemainder) * slop
                }
            }
            if (scrollStarted) {
                val distance = scrollRemainder.toInt()
                scrollRemainder -= distance
                scrollBy(0, distance)
            }
        }
        if (lifted == scrollIndex) scrollPointer = -1

        // ItemTouchHelper normally adopts a newly added pointer. Give it only
        // the original finger so scrolling cannot move or release the held card.
        val action = when {
            event.actionMasked == MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            event.actionMasked == MotionEvent.ACTION_UP || lifted == primaryIndex -> MotionEvent.ACTION_UP
            else -> MotionEvent.ACTION_MOVE
        }
        val properties = MotionEvent.PointerProperties().also { event.getPointerProperties(primaryIndex, it) }
        val coords = MotionEvent.PointerCoords().also { event.getPointerCoords(primaryIndex, it) }
        val single = MotionEvent.obtain(event.downTime, event.eventTime, action, 1, arrayOf(properties), arrayOf(coords),
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision, event.deviceId,
            event.edgeFlags, event.source, event.flags)
        heldEvent?.recycle()
        heldEvent = if (action == MotionEvent.ACTION_MOVE) MotionEvent.obtain(single) else null
        val handled = super.dispatchTouchEvent(single)
        single.recycle()
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swallowRemainingPointers = action == MotionEvent.ACTION_UP && event.pointerCount > 1
            endCardDrag()
        }
        return handled
    }

    override fun onDetachedFromWindow() {
        endCardDrag()
        super.onDetachedFromWindow()
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
