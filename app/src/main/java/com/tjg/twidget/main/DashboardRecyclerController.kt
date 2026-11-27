package com.tjg.twidget.main

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.DashboardRecyclerView
import com.tjg.twidget.ui.TwidgetHaptics

/** Owns the native list and its provisional drag order; saved state changes only on commit. */
internal class DashboardRecyclerController(private val activity: MainActivity, private val binder: MainDashboardBinder) {
    private var recycler: DashboardRecyclerView? = null
    private var account = ""
    private var editing = false
    private var items = emptyList<DashboardCardType>()
    private val overrides = mutableMapOf<String, Int>()
    private var dragging: String? = null
    private var incomingCard: String? = null
    private var cancelled = false
    private val dropTargets = DashboardDropTargetPolicy(activity.dp(6).toFloat())
    private var nestedScrollBeforeDrag: Boolean? = null
    private var headerAdapter: ChromeAdapter? = null
    private var footerAdapter: ChromeAdapter? = null
    private val cards = CardAdapter()
    private val landingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    val cardIds: List<String> get() = items.map { it.id }
    val isDragging get() = dragging != null || incomingCard != null
    fun span(card: DashboardCardType): Int = overrides[card.id] ?: card.span(activity, account.ifBlank { activity.selectedAccount })

    private val heldViewport = Rect()
    private var lastDragScrollTime = 0L
    private val edgeScrollRamp = DashboardEdgeScrollRamp()
    private var edgeScrollRemainder = 0f
    private val dragScroll = object : Runnable {
        override fun run() {
            val view = recycler ?: return
            if (dragging == null || !view.isHoldingCard) return
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = (now - lastDragScrollTime).coerceIn(1, 64)
            lastDragScrollTime = now
            if (!view.isScrollingHeldCard && view.getLocalVisibleRect(heldViewport)) {
                val edge = activity.dp(64).toFloat()
                val top = maxOf(heldViewport.top, view.paddingTop)
                val bottom = minOf(heldViewport.bottom, view.height - view.paddingBottom)
                val y = view.lastTouchY
                val proximity = when {
                    y < top + edge -> -((top + edge - y) / edge).coerceIn(0f, 1f)
                    y > bottom - edge -> ((y - bottom + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                val speed = edgeScrollRamp.speed(proximity, now)
                if (speed == 0f) edgeScrollRemainder = 0f
                edgeScrollRemainder += speed * activity.resources.displayMetrics.density * elapsed / 1000f
                val distance = edgeScrollRemainder.toInt()
                edgeScrollRemainder -= distance
                if (distance != 0 && view.canScrollVertically(distance)) {
                    view.scrollBy(0, distance)
                    view.refreshCardDrag()
                }
            } else {
                edgeScrollRamp.reset()
                edgeScrollRemainder = 0f
            }
            view.postOnAnimation(this)
        }
    }
    private val callback = object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(view: RecyclerView, holder: RecyclerView.ViewHolder): Int =
            if (editing && holder is CardHolder && !binder.isResizing)
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) else 0
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false
        override fun getAnimationDuration(view: RecyclerView, type: Int, dx: Float, dy: Float) = 200L
        override fun canDropOver(view: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            target is CardHolder
        override fun chooseDropTarget(selected: RecyclerView.ViewHolder, targets: MutableList<RecyclerView.ViewHolder>, x: Int, y: Int): RecyclerView.ViewHolder? {
            val view = recycler ?: return null
            val tile = selected.itemView
            val candidates = targets.filterIsInstance<CardHolder>().filter { it.bindingAdapterPosition >= 0 }
            val chosen = dropTargets.choose(DashboardDropTargetPolicy.Bounds(x.toFloat(), y.toFloat(),
                x + tile.width.toFloat(), y + tile.height.toFloat()), candidates.map {
                    val child = it.itemView
                    DashboardDropTargetPolicy.Target(it.cardId, DashboardDropTargetPolicy.Bounds(child.left.toFloat(),
                        child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat()))
                }, view.computeVerticalScrollOffset())
            return candidates.firstOrNull { it.cardId == chosen }
        }
        override fun onMove(view: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val source = (from as? CardHolder)?.cardId ?: return false
            val target = (to as? CardHolder)?.cardId ?: return false
            activity.editModeController.previewMoveDashboardCard(source, target,
                after = from.bindingAdapterPosition < to.bindingAdapterPosition)
            return true
        }
        override fun onMoved(view: RecyclerView, source: RecyclerView.ViewHolder, from: Int,
            target: RecyclerView.ViewHolder, to: Int, x: Int, y: Int) {
            // The default implementation calls prepareForDrop, which scrolls
            // Linear/GridLayoutManager to the target after each reorder.
            // ItemTouchHelper already keeps the dragged card under the pointer.
        }
        override fun interpolateOutOfBoundsScroll(view: RecyclerView, size: Int, outOfBounds: Int,
            totalSize: Int, elapsed: Long): Int = 0 // The visible-viewport loop owns edge scrolling.
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(holder, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && holder is CardHolder) {
                dropTargets.begin(holder.itemView.left.toFloat(), holder.itemView.top.toFloat(), recycler?.computeVerticalScrollOffset() ?: 0)
                holder.itemView.apply {
                    alpha = 1f
                    setTag(R.id.dashboard_drag_height, height)
                    DashboardCardMotion.lift(this)
                }
            }
        }
        override fun onChildDraw(canvas: Canvas, view: RecyclerView, holder: RecyclerView.ViewHolder,
            dx: Float, dy: Float, state: Int, active: Boolean) {
            if (state == ItemTouchHelper.ACTION_STATE_DRAG) {
                landingPaint.color = (activity.getColor(R.color.oneui_text_secondary) and 0x00ffffff) or 0x18000000
                val tile = holder.itemView
                val inset = activity.dp(2).toFloat()
                canvas.drawRoundRect(tile.left + inset, tile.top + inset, tile.right - inset, tile.bottom - inset,
                    activity.dp(28).toFloat(), activity.dp(28).toFloat(), landingPaint)
                tile.alpha = 1f
            }
            val tile = holder.itemView
            var horizontalOffset = dx
            var verticalOffset = dy
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && view.getLocalVisibleRect(heldViewport)) {
                // Use the visible rectangle, including ancestor toolbar clipping,
                // rather than the RecyclerView's potentially off-screen bounds.
                val margin = activity.dp(8)
                val left = maxOf(view.paddingLeft, heldViewport.left) + margin
                val right = minOf(view.width - view.paddingRight, heldViewport.right) - margin
                val top = maxOf(view.paddingTop, heldViewport.top) + margin
                val bottom = heldViewport.bottom - margin
                fun offset(value: Float, start: Int, end: Int, origin: Int, size: Int): Float =
                    if (end - start >= size) value.coerceIn((start - origin).toFloat(), (end - origin - size).toFloat())
                    else (start - origin).toFloat()
                // Full-width cards retain their normal gutters when there is no
                // room for extra shadow clearance.
                horizontalOffset = offset(dx, if (right-left >= tile.width) left else maxOf(view.paddingLeft, heldViewport.left),
                    if (right-left >= tile.width) right else minOf(view.width-view.paddingRight, heldViewport.right), tile.left, tile.width)
                verticalOffset = offset(dy, top, bottom, tile.top, tile.height)
            }
            super.onChildDraw(canvas, view, holder, horizontalOffset, verticalOffset, state, active)
        }
        override fun clearView(view: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(view, holder)
            holder.itemView.apply {
                alpha = 1f
                setTag(R.id.dashboard_drag_height, null)
                DashboardCardMotion.release(this)
                requestLayout()
            }
            restoreNestedScroll()
            (view as? DashboardRecyclerView)?.endCardDrag()
            view.removeCallbacks(dragScroll)
            dropTargets.reset()
            val id = (holder as? CardHolder)?.cardId
            if (id != null && dragging == id) {
                dragging = null
                activity.editModeController.finishDashboardDrag(commit = !cancelled)
            }
        }
    }
    private var touch = ItemTouchHelper(callback)

    fun attach(view: DashboardRecyclerView, header: View, footer: View) {
        if (recycler === view) return
        recycler?.apply {
            touch.attachToRecyclerView(null)
            adapter = null
            onGestureCancelled = null
            onAdditionalPointer = null
            removeCallbacks(dragScroll)
            endCardDrag()
        }
        recycler = view
        val manager = object : GridLayoutManager(activity, view.columnCount) {
            override fun supportsPredictiveItemAnimations() = false
        }
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0 || position == items.size + 1) manager.spanCount
                else items.getOrNull(position - 1)?.let(::span)?.coerceAtMost(manager.spanCount) ?: manager.spanCount
        }
        view.layoutManager = manager
        view.itemAnimator = DashboardItemAnimator()
        view.clipChildren = false
        view.clipToPadding = false
        view.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, child: View, parent: RecyclerView, state: RecyclerView.State) {
                if (parent.getChildViewHolder(child) is CardHolder) out.set(activity.dp(7), activity.dp(7), activity.dp(7), activity.dp(7))
                else out.setEmpty()
            }
        })
        headerAdapter = ChromeAdapter(header, 0)
        footerAdapter = ChromeAdapter(footer, 1)
        view.adapter = ConcatAdapter(ConcatAdapter.Config.Builder()
            .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS).build(), headerAdapter!!, cards, footerAdapter!!)
        touch.attachToRecyclerView(view)
        view.onGestureCancelled = { cancelled = true }
        view.onAdditionalPointer = {
            // RecyclerView splits extra pointers between child cards. Promote
            // the existing stationary hold before that split can hide it.
            (0 until view.childCount).any { index ->
                (view.getChildAt(index).getTag(R.id.dashboard_card_gesture) as? DashboardCardGesture)
                    ?.startDragFromHold() == true
            }
        }

    }

    fun submit(profile: String, next: List<DashboardCardType>, editing: Boolean) {
        val view = recycler ?: return
        val profileChanged = account != profile
        if (profileChanged) {
            cancelNativeDrag()
            view.itemAnimator?.endAnimations()
            overrides.clear()
        }
        val modeOnly = account == profile && items == next && this.editing != editing
        account = profile
        this.editing = editing
        replace(next)
        if (items.isNotEmpty()) cards.notifyItemRangeChanged(0, items.size, if (profileChanged) PROFILE else if (modeOnly) MODE else DATA)
        headerAdapter?.notifyItemChanged(0)
        footerAdapter?.notifyItemChanged(0)
    }

    private fun replace(next: List<DashboardCardType>) {
        val old = items
        if (old == next) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a] == next[b]
            override fun areContentsTheSame(a: Int, b: Int) = true
        })
        items = next
        (recycler?.layoutManager as? GridLayoutManager)?.spanSizeLookup?.invalidateSpanIndexCache()
        diff.dispatchUpdatesTo(cards)
    }

    fun applyOrder(order: List<String>) = replace(items.sortedBy { order.indexOf(it.id) })
    fun startDrag(card: DashboardCardType, wrapper: View): Boolean {
        val view = recycler ?: return false
        if (!editing || dragging != null || (incomingCard != null && incomingCard != card.id) || binder.isResizing) return false
        val holder = view.getChildViewHolder(wrapper) as? CardHolder ?: return false
        view.stopScroll()
        view.itemAnimator?.endAnimations()
        dropTargets.reset()
        nestedScrollBeforeDrag = view.isNestedScrollingEnabled
        view.isNestedScrollingEnabled = false
        if (incomingCard == null) activity.editModeController.beginDashboardDrag(card, wrapper)
        dragging = card.id
        cancelled = false
        view.beginCardDrag()
        edgeScrollRamp.reset()
        edgeScrollRemainder = 0f
        touch.startDrag(holder)
        lastDragScrollTime = android.os.SystemClock.uptimeMillis()
        view.postOnAnimation(dragScroll)
        return true
    }
    fun prepareAddedCard(card: DashboardCardType, span: Int, order: List<String>, rawY: Float, ready: (View?) -> Unit) {
        val view = recycler ?: return
        incomingCard = card.id
        overrides[card.id] = span
        cancelled = false
        val origin = IntArray(2).also(view::getLocationOnScreen)
        val y = rawY - origin[1]
        val target = (0 until view.childCount).map { view.getChildAt(it) }
            .filter { it.tag is String && it.tag in cardIds }
            .minByOrNull { kotlin.math.abs(it.top + it.height / 2f - y) }
        val targetId = target?.tag as? String
        val desiredOrder = order.toMutableList().apply {
            remove(card.id)
            add(indexOf(targetId).takeIf { it >= 0 } ?: size, card.id)
        }
        // Keep the transaction's order identical to the live list before pickup.
        if (targetId != null) activity.editModeController.previewMoveDashboardCard(card.id, targetId, false)
        replace((items + card).distinct().sortedBy { desiredOrder.indexOf(it.id) })
        val position = items.indexOf(card) + 1
        view.getLocalVisibleRect(heldViewport)
        val offset = (y - activity.dp(card.preferredHeightDp(activity)) / 2f).toInt()
            .coerceIn(heldViewport.top, maxOf(heldViewport.top, heldViewport.bottom - activity.dp(card.preferredHeightDp(activity))))
        (view.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(position, offset - view.paddingTop)
        view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                view.removeOnLayoutChangeListener(this)
                if (incomingCard != card.id) return
                val holder = (0 until view.childCount).map { view.getChildAt(it) }.firstOrNull { it.tag == card.id }
                if (holder == null) activity.editModeController.finishDashboardDrag(false)
                ready(holder)
            }
        })
        view.requestLayout()
    }
    fun finishDrag(order: List<String>) {
        cancelNativeDrag()
        val added = incomingCard
        incomingCard = null
        overrides.clear()
        val saved = order.mapNotNull(DashboardCardType::fromId).filter { it in items }
        replace(saved)
        if (added != null) items.indexOfFirst { it.id == added }.takeIf { it >= 0 }?.let { cards.notifyItemChanged(it, DATA) }
    }
    private fun restoreNestedScroll() {
        nestedScrollBeforeDrag?.let { recycler?.isNestedScrollingEnabled = it }
        nestedScrollBeforeDrag = null
    }
    private fun cancelNativeDrag() {
        recycler?.removeCallbacks(dragScroll)
        recycler?.endCardDrag()
        restoreNestedScroll()
        dropTargets.reset()
        if (dragging == null) return
        dragging = null
        cancelled = true
        val view = recycler ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        view.dispatchTouchEvent(cancel)
        cancel.recycle()
        touch.attachToRecyclerView(null)
        // A cancelled interaction must not retain the old helper's selected
        // holder or pointer when the activity resumes or the profile changes.
        touch = ItemTouchHelper(callback)
        touch.attachToRecyclerView(view)
    }

    fun previewResize(card: DashboardCardType, span: Int, order: List<String>) {
        overrides[card.id] = span
        applyOrder(order)
        (recycler?.layoutManager as? GridLayoutManager)?.spanSizeLookup?.invalidateSpanIndexCache()
        items.indexOf(card).takeIf { it >= 0 }?.let { cards.notifyItemChanged(it, RESIZE) }
    }
    fun finishResize(card: DashboardCardType, order: List<String>) {
        val preview = overrides.remove(card.id)
        applyOrder(order)
        (recycler?.layoutManager as? GridLayoutManager)?.spanSizeLookup?.invalidateSpanIndexCache()
        if (preview != card.span(activity, account))
            items.indexOf(card).takeIf { it >= 0 }?.let { cards.notifyItemChanged(it, RESIZE) }
    }
    fun scrollToCard(id: String, offset: Int = 0) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) (recycler?.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(index + 1, offset)
    }

    private inner class CardHolder(val wrapper: FrameLayout, val cardId: String) : RecyclerView.ViewHolder(wrapper) {
        var fresh = true
    }
    private inner class CardAdapter : RecyclerView.Adapter<CardHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = items.size
        override fun getItemId(position: Int) = items[position].ordinal.toLong()
        override fun getItemViewType(position: Int) = items[position].ordinal
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): CardHolder {
            val card = DashboardCardType.entries[type]
            val wrapper = binder.createRecyclerCard(card)
            wrapper.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(card.preferredHeightDp(activity)))
            return CardHolder(wrapper, card.id)
        }
        override fun onBindViewHolder(holder: CardHolder, position: Int) = onBindViewHolder(holder, position, mutableListOf(DATA))
        override fun onBindViewHolder(holder: CardHolder, position: Int, payloads: MutableList<Any>) {
            val card = items[position]
            holder.wrapper.setTag(R.id.dashboard_card_span, span(card))
            holder.wrapper.setTag(R.id.dashboard_preferred_height, card.preferredHeightDp(activity))
            holder.wrapper.layoutParams.height = activity.dp(card.preferredHeightDp(activity))
            binder.bindRecyclerCard(holder.wrapper, card, refreshContent = !holder.fresh && (payloads.isEmpty() || DATA in payloads || RESIZE in payloads || PROFILE in payloads),
                reuseContent = PROFILE !in payloads)
            holder.fresh = false
        }
    }
    private class ChromeAdapter(private val view: View, private val key: Long) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = 1
        override fun getItemId(position: Int) = key
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(view) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }
    private companion object { const val PROFILE = "dashboard_profile"; const val DATA = "dashboard_data"; const val RESIZE = "dashboard_resize"; const val MODE = "dashboard_mode" }
}
