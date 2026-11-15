package com.tjg.twidget.main

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.DashboardRecyclerView
import com.tjg.twidget.ui.TwidgetHaptics
import kotlin.math.abs

/** A grip previews discrete native span states, never fractional layouts for neighbouring cards. */
internal class DashboardCardResize(private val activity: MainActivity) {
    private data class Drag(val card: DashboardCardType, val account: String, val view: FrameLayout,
        val recycler: DashboardRecyclerView, val edge: DashboardResizeEdge, val originalSpan: Int,
        val originalOrder: List<String>, val spans: Map<String, Int>, val unit: Float,
        val originalEdge: Float, var previewSpan: Int, var order: List<String>, var distance: Float = 0f)
    private var drag: Drag? = null
    val isActive get() = drag != null

    fun createHandle(card: DashboardCardType, wrapper: FrameLayout, edge: DashboardResizeEdge = DashboardResizeEdge.RIGHT): View =
        ResizeHandle(card, wrapper, edge)
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
            wrapper.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (wrapper.getTag(R.id.dashboard_resize_animating) != true) updatePlacement()
                updateGrip()
            }
            isClickable = true
            isFocusable = true
        }
        private fun updateGrip() {
            val state = drag?.takeIf { it.view === wrapper && it.edge == edge }
            if (state == null) { translationX = 0f; return }
            val position = IntArray(2).also(wrapper::getLocationOnScreen)
            val current = position[0] + if (edge == DashboardResizeEdge.LEFT) 0f else wrapper.width * wrapper.scaleX
            translationX = (state.originalEdge + state.distance - current) / wrapper.scaleX
        }
        fun updatePlacement() {
            val recycler = wrapper.parent as? DashboardRecyclerView ?: return
            val params = wrapper.layoutParams as? GridLayoutManager.LayoutParams ?: return
            val span = params.spanSize.takeIf { it > 0 } ?: return
            val logical = params.spanIndex.takeIf { it >= 0 } ?: return
            val column = if (recycler.layoutDirection == View.LAYOUT_DIRECTION_RTL) recycler.columnCount - logical - span else logical
            val edges = DashboardResizePolicy.edges(column, span, recycler.columnCount)
            visibility = if (edge in edges || drag?.let { it.view === wrapper && it.edge == edge } == true) View.VISIBLE else View.GONE
            val expanded = (wrapper.getTag(R.id.dashboard_card_span) as? Int) == 2
            val label = if (expanded) {
                if (edge == DashboardResizeEdge.LEFT) R.string.dashboard_shrink_card_left else R.string.dashboard_shrink_card_right
            } else if (edge == DashboardResizeEdge.LEFT) R.string.dashboard_expand_card_left else R.string.dashboard_expand_card_right
            contentDescription = activity.getString(label, activity.getString(card.labelRes))
            val width = if (edges.size == 2) minOf(activity.dp(64), ((wrapper.width - activity.dp(48)) / 2).coerceAtLeast(activity.dp(24))) else activity.dp(64)
            (layoutParams as? FrameLayout.LayoutParams)?.let { if (it.width != width) { it.width = width; layoutParams = it } }
        }
        override fun onDraw(canvas: Canvas) {
            val unit = wrapper.height / 214f
            val x = if (edge == DashboardResizeEdge.LEFT) 4f * unit else width - 4f * unit
            pill.set(x - 3.5f * unit, height / 2f - 30f * unit, x + 3.5f * unit, height / 2f + 30f * unit)
            paint.color = context.getColor(R.color.oneui_text_primary)
            paint.alpha = if (isPressed) 220 else 150
            canvas.drawRoundRect(pill, 4f * unit, 4f * unit, paint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    accepted = begin(card, wrapper, edge)
                    downX = event.rawX; moved = false; isPressed = accepted
                    if (accepted) parent.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (accepted) {
                    val distance = event.rawX - downX
                    if (abs(distance) > slop) moved = true
                    if (moved) { preview(distance); updateGrip() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    if (accepted && !moved && event.actionMasked == MotionEvent.ACTION_UP) super.performClick()
                    if (accepted) finish(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL, toggle = !moved)
                    accepted = false; translationX = 0f; invalidate()
                }
            }
            return true
        }
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.Button::class.java.name
        }
        override fun performClick(): Boolean { super.performClick(); toggle(card, edge); return true }
        override fun onDetachedFromWindow() {
            drag?.takeIf { it.view === wrapper }?.let { state ->
                state.recycler.post { if (drag === state) finish(cancelled = true, toggle = false) }
            }
            super.onDetachedFromWindow()
        }
    }
    private fun refreshHandles(view: FrameLayout) {
        (0 until view.childCount).map { view.getChildAt(it) }.filterIsInstance<ResizeHandle>().forEach { it.updatePlacement() }
    }
    fun toggle(card: DashboardCardType, edge: DashboardResizeEdge? = null) {
        if (isActive) return
        val recycler = activity.findViewById<DashboardRecyclerView>(R.id.dashboard_content) ?: return
        val wrapper = recycler.findViewWithTag<FrameLayout>(card.id) ?: return
        refreshHandles(wrapper)
        val handles = (0 until wrapper.childCount).map { wrapper.getChildAt(it) }.filterIsInstance<ResizeHandle>()
        val flowEnd = if (wrapper.layoutDirection == View.LAYOUT_DIRECTION_RTL) DashboardResizeEdge.LEFT else DashboardResizeEdge.RIGHT
        val selected = edge ?: handles.firstOrNull { it.visibility == View.VISIBLE && it.edge == flowEnd }?.edge
            ?: handles.firstOrNull { it.visibility == View.VISIBLE }?.edge ?: return
        if (begin(card, wrapper, selected)) finish(cancelled = false, toggle = true)
    }
    private fun begin(card: DashboardCardType, wrapper: FrameLayout, edge: DashboardResizeEdge): Boolean {
        if (isActive) return false
        val recycler = wrapper.parent as? DashboardRecyclerView ?: return false
        if (!activity.editModeController.beginResize()) return false
        recycler.stopScroll()
        recycler.itemAnimator?.endAnimations()
        val order = TwidgetStore.dashboardCards(activity, activity.selectedAccount)
        val spans = activity.dashboardBinder.cardIds().associateWith { DashboardCardType.fromId(it)!!.span(activity, activity.selectedAccount) }
        val original = card.span(activity, activity.selectedAccount)
        val position = IntArray(2).also(wrapper::getLocationOnScreen)
        drag = Drag(card, activity.selectedAccount, wrapper, recycler, edge, original, order, spans,
            (recycler.width - recycler.paddingLeft - recycler.paddingRight).toFloat() / recycler.columnCount * wrapper.scaleX,
            position[0] + if (edge == DashboardResizeEdge.LEFT) 0f else wrapper.width * wrapper.scaleX, original, order)
        TwidgetHaptics.selection(wrapper)
        return true
    }
    private fun preview(distance: Float) {
        val state = drag ?: return
        state.distance = distance.coerceIn(-state.unit, state.unit)
        val fraction = (state.originalSpan - 1 + distance * state.edge.direction / state.unit).coerceIn(0f, 1f)
        setPreview(state, DashboardResizePolicy.previewSpan(fraction, state.previewSpan))
    }
    private fun setPreview(state: Drag, span: Int) {
        if (span == state.previewSpan) return
        state.previewSpan = span
        state.order = DashboardResizePolicy.orderForResize(state.originalOrder, state.spans, state.card.id, span,
            state.edge, state.recycler.layoutDirection == View.LAYOUT_DIRECTION_RTL, state.recycler.columnCount)
        activity.dashboardBinder.previewCardResize(state.card, span, state.order)
        TwidgetHaptics.selection(state.view)
    }
    private fun finish(cancelled: Boolean, toggle: Boolean) {
        val state = drag ?: return
        if (toggle && !cancelled) setPreview(state, if (state.originalSpan == 1) 2 else 1)
        drag = null
        (0 until state.view.childCount).map { state.view.getChildAt(it) }.filterIsInstance<ResizeHandle>().forEach { it.translationX = 0f }
        activity.editModeController.finishResize(state.card, state.previewSpan, state.order,
            commit = !cancelled && state.account == activity.selectedAccount)
        state.recycler.post { refreshHandles(state.view) }
    }
    fun dispose() { finish(cancelled = true, toggle = false) }
}
