package com.tjg.twidget.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.CardValueTextView
import com.tjg.twidget.ui.DashboardGridLayout
import com.tjg.twidget.ui.TwidgetHaptics
import kotlin.math.abs
import kotlin.math.roundToInt

/** Resizes native card layouts; the grid's column spans change only when the drag snaps. */
internal class DashboardCardResize(private val activity: MainActivity) {
    private data class Slots(val bounds: Map<View, Rect>, val height: Int)
    private data class Drag(val card: DashboardCardType, val account: String, val view: FrameLayout,
        val grid: DashboardGridLayout, val bounds: Rect, val small: Int, val large: Int,
        val smallSlots: Slots, val largeSlots: Slots)
    private var drag: Drag? = null
    private var animator: ValueAnimator? = null
    private var lastResizeStep = 0
    private var lastResizeTick = 0L
    val isActive: Boolean get() = drag != null || animator != null

    fun createHandle(card: DashboardCardType, wrapper: FrameLayout): View = object : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pill = RectF()
        private var downX = 0f
        private var moved = false
        private var accepted = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        init {
            tag = "dashboard_resize_${card.id}"
            isClickable = true
            isFocusable = true
            contentDescription = activity.getString(if (card.span(activity, activity.selectedAccount) == 1)
                R.string.dashboard_expand_card else R.string.dashboard_shrink_card, activity.getString(card.labelRes))
        }
        override fun onDraw(canvas: Canvas) {
            val unit = wrapper.height / 214f
            val x = width - 4f * unit
            pill.set(x - 3.5f * unit, height / 2f - 30f * unit, x + 3.5f * unit, height / 2f + 30f * unit)
            paint.style = Paint.Style.FILL
            paint.color = context.getColor(R.color.oneui_text_primary)
            paint.alpha = if (isPressed) 220 else 150
            canvas.drawRoundRect(pill, 4f * unit, 4f * unit, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    accepted = begin(card, wrapper)
                    downX = event.rawX
                    moved = false
                    isPressed = accepted
                    parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (accepted) {
                    val distance = event.rawX - downX
                    if (abs(distance) > slop) moved = true
                    if (moved) preview(distance / wrapper.scaleX)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    if (accepted && !moved && event.actionMasked == MotionEvent.ACTION_UP) super.performClick()
                    if (accepted) finish(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL, toggle = !moved)
                    accepted = false
                    invalidate()
                }
            }
            return true
        }
        override fun performClick(): Boolean {
            super.performClick()
            toggle(card)
            return true
        }
        override fun onDetachedFromWindow() {
            if (drag?.view === wrapper) {
                val grid = drag?.grid
                drag = null
                grid?.clearResizePreview()
            }
            super.onDetachedFromWindow()
        }
    }

    fun toggle(card: DashboardCardType) {
        if (isActive) return
        animateTo(card, if (card.span(activity, activity.selectedAccount) == 1) 2 else 1)
    }

    private fun begin(card: DashboardCardType, wrapper: FrameLayout): Boolean {
        if (isActive || !activity.editModeController.editMode || activity.editModeController.draggedCardId != null) return false
        val grid = wrapper.parent as? DashboardGridLayout ?: return false
        val originalSpan = card.span(activity, activity.selectedAccount)
        fun slots(span: Int): Slots {
            wrapper.setTag(R.id.dashboard_card_span, span)
            for (index in 0 until grid.childCount) {
                val view = grid.getChildAt(index)
                val params = view.layoutParams as GridLayout.LayoutParams
                val columns = view.getTag(R.id.dashboard_card_span) as Int
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, columns, 1f)
                params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED)
                view.layoutParams = params
            }
            grid.columnCount = grid.columnCount
            grid.measure(View.MeasureSpec.makeMeasureSpec(grid.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            grid.layout(grid.left, grid.top, grid.right, grid.top + grid.measuredHeight)
            return Slots((0 until grid.childCount).associate { index ->
                val view = grid.getChildAt(index)
                view to Rect(view.left, view.top, view.right, view.bottom)
            }, grid.measuredHeight)
        }
        val small = slots(1)
        val large = slots(2)
        val original = slots(originalSpan)
        val bounds = original.bounds.getValue(wrapper)
        drag = Drag(card, activity.selectedAccount, wrapper, grid, bounds,
            small.bounds.getValue(wrapper).width(), large.bounds.getValue(wrapper).width(), small, large)
        grid.setResizePreview(original.bounds, original.height)
        lastResizeStep = if (card.span(activity, activity.selectedAccount) == 1) 0 else 8
        lastResizeTick = 0L
        wrapper.translationZ = activity.dp(20).toFloat()
        TwidgetHaptics.selection(wrapper)
        return true
    }

    private fun preview(distance: Float) {
        val state = drag ?: return
        val direction = if (state.view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val width = (state.bounds.width() + distance * direction).roundToInt().coerceIn(state.small, state.large)
        val fraction = (width - state.small).toFloat() / (state.large - state.small).coerceAtLeast(1)
        val step = (fraction * 8).roundToInt()
        val now = SystemClock.uptimeMillis()
        if (step != lastResizeStep && now - lastResizeTick >= 45L) {
            if (Build.VERSION.SDK_INT >= 34) TwidgetHaptics.dragHold(state.view) else TwidgetHaptics.selection(state.view)
            lastResizeStep = step
            lastResizeTick = now
        }
        val bounds = state.smallSlots.bounds.mapValues { (view, small) ->
            val large = state.largeSlots.bounds.getValue(view)
            fun interpolate(start: Int, end: Int) = (start + (end - start) * fraction).roundToInt()
            Rect(interpolate(small.left, large.left), interpolate(small.top, large.top),
                interpolate(small.right, large.right), interpolate(small.bottom, large.bottom))
        }
        val height = (state.smallSlots.height + (state.largeSlots.height - state.smallSlots.height) * fraction).roundToInt()
        state.grid.setResizePreview(bounds, height)
    }

    private fun finish(cancelled: Boolean, toggle: Boolean) {
        val state = drag ?: return
        if (state.account != activity.selectedAccount) {
            drag = null
            state.view.translationZ = 0f
            state.grid.clearResizePreview()
            activity.render(refreshRemote = false)
            return
        }
        val current = state.card.span(activity, state.account)
        val target = when {
            cancelled -> current
            toggle -> if (current == 1) 2 else 1
            state.view.width >= (state.small + state.large) / 2 -> 2
            else -> 1
        }
        drag = null
        state.view.translationZ = 0f
        animateTo(state.card, target, feedback = !cancelled)
    }

    private fun animateTo(card: DashboardCardType, targetSpan: Int, feedback: Boolean = true) {
        val grid = activity.findViewById<GridLayout>(R.id.dashboard_content) ?: return
        val previous = (0 until grid.childCount).associate { index ->
            val view = grid.getChildAt(index)
            view.tag.toString() to Rect(view.left, view.top, view.right, view.bottom)
        }
        val old = grid.findViewWithTag<FrameLayout>(card.id) ?: return
        val oldContent = content(old)
        val changed = card.span(activity, activity.selectedAccount) != targetSpan
        val snapshot = if (changed && oldContent != null && oldContent.width > 0 && oldContent.height > 0)
            Bitmap.createBitmap(oldContent.width, oldContent.height, Bitmap.Config.ARGB_8888).also { oldContent.draw(Canvas(it)) } else null
        (grid as? DashboardGridLayout)?.clearResizePreview()
        suppress(grid, false)
        TwidgetStore.setDashboardCardExpanded(activity, card.id, targetSpan == 2, activity.selectedAccount)
        if (feedback) TwidgetHaptics.confirm(old)
        activity.render()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.viewTreeObserver.removeOnPreDrawListener(this)
                val resized = grid.findViewWithTag<FrameLayout>(card.id) ?: return true
                val start = previous[card.id] ?: return true
                val end = Rect(resized.left, resized.top, resized.right, resized.bottom)
                val views = (0 until grid.childCount).map { grid.getChildAt(it) }
                val positions = views.associateWith { view ->
                    val before = previous[view.tag.toString()]
                    (before?.left?.minus(view.left) ?: 0).toFloat() to (before?.top?.minus(view.top) ?: 0).toFloat()
                }
                val fresh = content(resized)
                val overlay = snapshot?.let { bitmap -> ImageView(activity).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }.also { resized.addView(it, resized.indexOfChild(fresh) + 1, FrameLayout.LayoutParams(-1, -1)) } }
                suppress(grid, true)
                fun frame(progress: Float) {
                    views.forEach { view ->
                        val position = positions.getValue(view)
                        view.translationX = position.first * (1f - progress)
                        view.translationY = position.second * (1f - progress)
                    }
                    val width = (start.width() + (end.width() - start.width()) * progress).roundToInt()
                    resized.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(end.height(), View.MeasureSpec.EXACTLY))
                    resized.translationX = 0f
                    resized.translationY = 0f
                    val left = (start.left + (end.left - start.left) * progress).roundToInt()
                    val top = (start.top + (end.top - start.top) * progress).roundToInt()
                    resized.layout(left, top, left + width, top + end.height())
                    if (overlay != null) {
                        fresh?.alpha = progress
                        overlay.alpha = 1f - progress
                    }
                }
                frame(0f)
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 240L
                    interpolator = PathInterpolator(0.2f, 0f, 0.2f, 1f)
                    addUpdateListener { frame(it.animatedValue as Float) }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            frame(1f)
                            overlay?.let(resized::removeView)
                            fresh?.alpha = 1f
                            animator = null
                            suppress(grid, false)
                            grid.requestLayout()
                        }
                    })
                    start()
                }
                return true
            }
        })
    }

    fun dispose() {
        drag?.grid?.clearResizePreview()
        drag = null
        animator?.cancel()
        animator = null
    }

    private fun content(wrapper: FrameLayout): View? = (0 until wrapper.childCount).map { wrapper.getChildAt(it) }
        .firstOrNull { it.getTag(R.id.dashboard_card_content) == true }

    private fun suppress(grid: GridLayout?, suppress: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) grid?.suppressLayout(suppress)
    }
}
