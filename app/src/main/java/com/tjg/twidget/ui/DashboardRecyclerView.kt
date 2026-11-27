package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.widget.OverScroller
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
    private var dragVelocity: VelocityTracker? = null
    private val heldScroller = OverScroller(context)
    private var flingY = 0
    private val heldFling = object : Runnable {
        override fun run() {
            if (!isHoldingCard || !heldScroller.computeScrollOffset()) return
            val dy = heldScroller.currY - flingY
            flingY = heldScroller.currY
            if (dy != 0 && !canScrollVertically(dy)) { heldScroller.abortAnimation(); return }
            scrollBy(0, dy)
            refreshCardDrag()
            postOnAnimation(this)
        }
    }
    private fun stopHeldFling() {
        heldScroller.abortAnimation()
        removeCallbacks(heldFling)
    }
    private var heldEvent: MotionEvent? = null
    internal var isHoldingCard = false
        private set
    internal val isScrollingHeldCard get() = scrollPointer != -1 || !heldScroller.isFinished
    private var swallowRemainingPointers = false

    internal fun beginCardDrag() {
        isHoldingCard = true
        scrollPointer = -1
    }

    internal fun endCardDrag() {
        if (isHoldingCard) stopScroll()
        isHoldingCard = false
        scrollPointer = -1
        dragVelocity?.recycle()
        dragVelocity = null
        stopHeldFling()
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
            stopHeldFling()
            dragVelocity?.recycle()
            dragVelocity = VelocityTracker.obtain()
            scrollPointer = event.getPointerId(event.actionIndex)
            scrollY = event.getY(event.actionIndex)
            scrollRemainder = 0f
            scrollStarted = false
        }
        val scrollIndex = event.findPointerIndex(scrollPointer)
        // Match RecyclerView: calculate release velocity from movement samples,
        // before adding the pointer-up event (which can clear that pointer's history).
        if (scrollIndex >= 0 && (event.actionMasked == MotionEvent.ACTION_MOVE ||
                event.actionMasked == MotionEvent.ACTION_POINTER_DOWN)) {
            for (history in 0..event.historySize) {
                val current = history == event.historySize
                val sample = MotionEvent.obtain(event.downTime,
                    if (current) event.eventTime else event.getHistoricalEventTime(history),
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE,
                    if (current) event.getX(scrollIndex) else event.getHistoricalX(scrollIndex, history),
                    if (current) event.getY(scrollIndex) else event.getHistoricalY(scrollIndex, history), 0)
                sample.source = event.source
                dragVelocity?.addMovement(sample)
                sample.recycle()
            }
        }
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
        if (lifted >= 0 && lifted == scrollIndex) {
            if (scrollStarted && lifted != primaryIndex) {
                val velocity = dragVelocity
                velocity?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                val speed = -(velocity?.yVelocity ?: 0f).toInt()
                if (kotlin.math.abs(speed) >= ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                    flingY = 0
                    // RecyclerView's own flinger is cancelled by ItemTouchHelper's
                    // interception. Use the same Android physics alongside the hold.
                    heldScroller.fling(0, 0, 0, speed, 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                    postOnAnimation(heldFling)
                }
            }
            scrollPointer = -1
            dragVelocity?.recycle()
            dragVelocity = null
        }

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
