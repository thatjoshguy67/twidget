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
    private data class Slots(val bounds: Map<View, Rect>, val height: Int, val order: List<String>)
    private data class Drag(val card: DashboardCardType, val account: String, val view: FrameLayout,
        val grid: DashboardGridLayout, val bounds: Rect, val small: Int, val large: Int,
        val smallSlots: Slots, val largeSlots: Slots, val edge: DashboardResizeEdge, val originalOrder: List<String>)
    private var drag: Drag? = null
    private var animator: ValueAnimator? = null
    private var lastResizeStep = 0
    private var lastResizeTick = 0L
    private var calculatingSlots = false
    private var snapPending = false
    val isActive: Boolean get() = drag != null || animator != null || calculatingSlots || snapPending

    fun createHandle(card: DashboardCardType, wrapper: FrameLayout, edge: DashboardResizeEdge = DashboardResizeEdge.RIGHT): View = ResizeHandle(card, wrapper, edge)

    private inner class ResizeHandle(val card: DashboardCardType, val wrapper: FrameLayout, val edge: DashboardResizeEdge) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pill = RectF()
        private var downX = 0f
        private var moved = false
        private var accepted = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        init {
            tag = "dashboard_resize_${card.id}" + if (edge == DashboardResizeEdge.LEFT) "_left" else ""
            setTag(R.id.dashboard_resize_left_edge, edge == DashboardResizeEdge.LEFT)
            wrapper.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!isActive) updatePlacement() }
            isClickable = true
            isFocusable = true
            val expanded = card.span(activity, activity.selectedAccount) == 2
            val label = if (expanded) {
                if (edge == DashboardResizeEdge.LEFT) R.string.dashboard_shrink_card_left else R.string.dashboard_shrink_card_right
            } else {
                if (edge == DashboardResizeEdge.LEFT) R.string.dashboard_expand_card_left else R.string.dashboard_expand_card_right
            }
            contentDescription = activity.getString(label, activity.getString(card.labelRes))
        }
        fun updatePlacement() {
            val grid = wrapper.parent as? DashboardGridLayout ?: return
            if (grid.width <= 0 || wrapper.width <= 0) return
            val span = wrapper.getTag(R.id.dashboard_card_span) as? Int ?: return
            val params = wrapper.layoutParams as GridLayout.LayoutParams
            val unit = (grid.width - grid.paddingLeft - grid.paddingRight).toFloat() / grid.columnCount
            val column = ((wrapper.left - params.leftMargin - grid.paddingLeft) / unit).roundToInt()
            val edges = DashboardResizePolicy.edges(column, span, grid.columnCount)
            visibility = if (edge in edges) View.VISIBLE else View.GONE
            val hitWidth = if (edges.size == 2) minOf(activity.dp(64), ((wrapper.width - activity.dp(48)) / 2).coerceAtLeast(activity.dp(24))) else activity.dp(64)
            (layoutParams as? FrameLayout.LayoutParams)?.let {
                if (it.width != hitWidth) { it.width = hitWidth; layoutParams = it }
            }
        }
        override fun onDraw(canvas: Canvas) {
            val unit = wrapper.height / 214f
            val x = if (edge == DashboardResizeEdge.LEFT) 4f * unit else width - 4f * unit
            pill.set(x - 3.5f * unit, height / 2f - 30f * unit, x + 3.5f * unit, height / 2f + 30f * unit)
            paint.style = Paint.Style.FILL
            paint.color = context.getColor(R.color.oneui_text_primary)
            paint.alpha = if (isPressed) 220 else 150
            canvas.drawRoundRect(pill, 4f * unit, 4f * unit, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    accepted = begin(card, wrapper, edge)
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
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.Button::class.java.name
        }
        override fun performClick(): Boolean {
            super.performClick()
            toggle(card, edge)
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

    private fun refreshHandles(grid: GridLayout) {
        for (index in 0 until grid.childCount) {
            val wrapper = grid.getChildAt(index) as? ViewGroup ?: continue
            (0 until wrapper.childCount).map { wrapper.getChildAt(it) }.filterIsInstance<ResizeHandle>().forEach { it.updatePlacement() }
        }
    }

    fun toggle(card: DashboardCardType, edge: DashboardResizeEdge? = null) {
        if (isActive) return
        val grid = activity.findViewById<DashboardGridLayout>(R.id.dashboard_content) ?: return
        val wrapper = grid.findViewWithTag<FrameLayout>(card.id) ?: return
        refreshHandles(grid)
        val handles = (0 until wrapper.childCount).map { wrapper.getChildAt(it) }.filterIsInstance<ResizeHandle>()
        val flowEnd = if (wrapper.layoutDirection == View.LAYOUT_DIRECTION_RTL) DashboardResizeEdge.LEFT else DashboardResizeEdge.RIGHT
        val selected = edge ?: handles.firstOrNull { it.visibility == View.VISIBLE && it.edge == flowEnd }?.edge
            ?: handles.firstOrNull { it.visibility == View.VISIBLE }?.edge ?: flowEnd
        if (begin(card, wrapper, selected)) finish(cancelled = false, toggle = true)
    }

    private fun begin(card: DashboardCardType, wrapper: FrameLayout, edge: DashboardResizeEdge): Boolean {
        if (isActive || !activity.editModeController.editMode || activity.editModeController.draggedCardId != null) return false
        val grid = wrapper.parent as? DashboardGridLayout ?: return false
        val originalSpan = card.span(activity, activity.selectedAccount)
        val originalOrder = TwidgetStore.dashboardCards(activity, activity.selectedAccount)
        val views = (0 until grid.childCount).map { grid.getChildAt(it) }
        val rtl = wrapper.layoutDirection == View.LAYOUT_DIRECTION_RTL
        var smallOrder = originalOrder
        var largeOrder = originalOrder
        if (DashboardResizePolicy.againstFlow(edge, rtl)) {
            if (originalSpan == 1) {
                val unit = (grid.width - grid.paddingLeft - grid.paddingRight).toFloat() / grid.columnCount
                val expanded = Rect(wrapper.left, wrapper.top, wrapper.right, wrapper.bottom).apply {
                    if (edge == DashboardResizeEdge.LEFT) left -= unit.roundToInt() else right += unit.roundToInt()
                }
                val collision = views.filter { it !== wrapper && it.top == wrapper.top &&
                    Rect.intersects(expanded, Rect(it.left, it.top, it.right, it.bottom)) }
                    .minByOrNull { originalOrder.indexOf(it.tag.toString()) }
                largeOrder = DashboardResizePolicy.moveBefore(originalOrder, card.id, collision?.tag?.toString())
                // If a two-column neighbour is displaced, fill its extra cell with
                // the next small card where possible to keep the outer edge anchored.
                if (collision?.getTag(R.id.dashboard_card_span) == 2) {
                    val filler = views.firstOrNull { originalOrder.indexOf(it.tag.toString()) > originalOrder.indexOf(card.id) && it.getTag(R.id.dashboard_card_span) == 1 }
                    if (filler != null) largeOrder = DashboardResizePolicy.moveBefore(largeOrder, filler.tag.toString(), card.id)
                }
            } else {
                val filler = views.firstOrNull { originalOrder.indexOf(it.tag.toString()) > originalOrder.indexOf(card.id) && it.getTag(R.id.dashboard_card_span) == 1 }
                if (filler != null) smallOrder = DashboardResizePolicy.moveBefore(originalOrder, filler.tag.toString(), card.id)
            }
        }
        fun slots(span: Int, order: List<String>): Slots {
            order.mapNotNull { id -> views.firstOrNull { it.tag == id } }.forEach(grid::bringChildToFront)
            wrapper.setTag(R.id.dashboard_card_span, span)
            for (index in 0 until grid.childCount) {
                val view = grid.getChildAt(index)
                val params = view.layoutParams as GridLayout.LayoutParams
                val columns = view.getTag(R.id.dashboard_card_span) as Int
                params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, columns, 1f)
                params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL)
                view.layoutParams = params
            }
            grid.columnCount = grid.columnCount
            grid.measure(View.MeasureSpec.makeMeasureSpec(grid.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            grid.layout(grid.left, grid.top, grid.right, grid.top + grid.measuredHeight)
            return Slots((0 until grid.childCount).associate { index ->
                val view = grid.getChildAt(index)
                view to Rect(view.left, view.top, view.right, view.bottom)
            }, grid.measuredHeight, order)
        }
        calculatingSlots = true
        val endpoints = try {
            Triple(slots(1, smallOrder), slots(2, largeOrder), slots(originalSpan, originalOrder))
        } finally { calculatingSlots = false }
        val (small, large, original) = endpoints
        val bounds = original.bounds.getValue(wrapper)
        drag = Drag(card, activity.selectedAccount, wrapper, grid, bounds,
            small.bounds.getValue(wrapper).width(), large.bounds.getValue(wrapper).width(), small, large, edge, originalOrder)
        grid.setResizePreview(original.bounds, original.height)
        lastResizeStep = if (card.span(activity, activity.selectedAccount) == 1) 0 else 8
        lastResizeTick = 0L
        wrapper.translationZ = activity.dp(20).toFloat()
        TwidgetHaptics.selection(wrapper)
        return true
    }

    private fun preview(distance: Float) {
        val state = drag ?: return
        val direction = state.edge.direction
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
        (0 until state.view.childCount).map { state.view.getChildAt(it) }
            .firstOrNull { it.getTag(R.id.dashboard_card_content) == true }
            ?.setTag(R.id.dashboard_stat_expanded, fraction > .15f)
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
        val order = if (cancelled) state.originalOrder else if (target == 2) state.largeSlots.order else state.smallSlots.order
        animateTo(state.card, target, feedback = !cancelled, order = order)
    }

    private fun animateTo(card: DashboardCardType, targetSpan: Int, feedback: Boolean = true, order: List<String>? = null) {
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
        if (order != null && order != TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            TwidgetStore.saveDashboardCards(activity, order, activity.selectedAccount)
        if (feedback) TwidgetHaptics.confirm(old)
        activity.render()
        if (!ValueAnimator.areAnimatorsEnabled()) { grid.post { refreshHandles(grid) }; return }
        snapPending = true
        grid.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                grid.viewTreeObserver.removeOnPreDrawListener(this)
                val resized = grid.findViewWithTag<FrameLayout>(card.id)
                val start = previous[card.id]
                if (resized == null || start == null) { snapPending = false; return true }
                refreshHandles(grid)
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
                            snapPending = false
                            refreshHandles(grid)
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
        snapPending = false
        calculatingSlots = false
    }

    private fun content(wrapper: FrameLayout): View? = (0 until wrapper.childCount).map { wrapper.getChildAt(it) }
        .firstOrNull { it.getTag(R.id.dashboard_card_content) == true }

    private fun suppress(grid: GridLayout?, suppress: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) grid?.suppressLayout(suppress)
    }
}
