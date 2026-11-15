package com.tjg.twidget.main

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.DragEvent
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
import kotlin.math.abs

/** Owns the native list and its provisional drag order; saved state changes only on commit. */
internal class DashboardRecyclerController(private val activity: MainActivity, private val binder: MainDashboardBinder) {
    private var recycler: DashboardRecyclerView? = null
    private var account = ""
    private var editing = false
    private var items = emptyList<DashboardCardType>()
    private val overrides = mutableMapOf<String, Int>()
    private var dragging: String? = null
    private var external: String? = null
    private var cancelled = false
    private var headerAdapter: ChromeAdapter? = null
    private var footerAdapter: ChromeAdapter? = null
    private val cards = CardAdapter()
    private val landingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var externalX = 0f
    private var externalY = 0f
    private var lastExternalX = Float.NaN
    private var lastExternalY = Float.NaN
    private val externalScroll = object : Runnable {
        override fun run() {
            val view = recycler ?: return
            if (external == null) return
            val edge = activity.dp(64)
            val distance = when {
                externalY < edge -> -((edge - externalY) / edge * activity.dp(8)).toInt()
                externalY > view.height - edge -> ((externalY - view.height + edge) / edge * activity.dp(8)).toInt()
                else -> 0
            }
            if (distance != 0 && view.canScrollVertically(distance)) {
                view.scrollBy(0, distance)
                updateExternalTarget(force = true)
            }
            view.postOnAnimation(this)
        }
    }

    val cardIds: List<String> get() = items.map { it.id }
    val isDragging get() = dragging != null || external != null
    fun span(card: DashboardCardType): Int = overrides[card.id] ?: card.span(activity, account.ifBlank { activity.selectedAccount })

    private val callback = object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(view: RecyclerView, holder: RecyclerView.ViewHolder): Int =
            if (editing && holder is CardHolder && external == null && !binder.isResizing)
                makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) else 0
        override fun isLongPressDragEnabled() = false
        override fun isItemViewSwipeEnabled() = false
        override fun canDropOver(view: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) =
            target is CardHolder && target.cardId != external
        override fun chooseDropTarget(selected: RecyclerView.ViewHolder, targets: MutableList<RecyclerView.ViewHolder>, x: Int, y: Int): RecyclerView.ViewHolder? {
            val view = recycler ?: return null
            val selectedCard = DashboardCardType.fromId(selected.itemView.tag as? String ?: "") ?: return null
            val filtered = targets.filter { target ->
                val targetCard = DashboardCardType.fromId(target.itemView.tag as? String ?: "")
                targetCard != null && (view.columnCount != 2 || span(selectedCard) == span(targetCard) ||
                    if (selected.bindingAdapterPosition < target.bindingAdapterPosition)
                        y + selected.itemView.height / 2 > target.itemView.top + target.itemView.height / 2
                    else y + selected.itemView.height / 2 < target.itemView.top + target.itemView.height / 2)
            }.toMutableList()
            return super.chooseDropTarget(selected, filtered, x, y)
        }
        override fun onMove(view: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val source = (from as? CardHolder)?.cardId ?: return false
            val target = (to as? CardHolder)?.cardId ?: return false
            activity.editModeController.previewMoveDashboardCard(source, target,
                after = from.bindingAdapterPosition < to.bindingAdapterPosition)
            return true
        }
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
            super.onSelectedChanged(holder, state)
            if (state == ItemTouchHelper.ACTION_STATE_DRAG && holder is CardHolder) holder.itemView.alpha = 1f
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
            super.onChildDraw(canvas, view, holder, dx, dy, state, active)
        }
        override fun clearView(view: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(view, holder)
            holder.itemView.alpha = 1f
            val id = (holder as? CardHolder)?.cardId
            if (id != null && dragging == id) {
                dragging = null
                activity.editModeController.finishDashboardDrag(commit = !cancelled)
            }
        }
    }
    private val touch = ItemTouchHelper(callback)

    fun attach(view: DashboardRecyclerView, header: View, footer: View) {
        if (recycler === view) return
        recycler?.apply {
            touch.attachToRecyclerView(null)
            adapter = null
            onGestureCancelled = null
            setOnDragListener(null)
            removeCallbacks(externalScroll)
        }
        recycler = view
        val manager = object : GridLayoutManager(activity, if (activity.resources.configuration.screenWidthDp >= 600) 4 else 2) {
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
        view.setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> external != null && event.localState == external
                DragEvent.ACTION_DRAG_LOCATION -> {
                    externalX = event.x; externalY = event.y; updateExternalTarget(); true
                }
                DragEvent.ACTION_DROP -> { activity.editModeController.finishDashboardDrag(true); true }
                DragEvent.ACTION_DRAG_ENDED -> { activity.editModeController.finishDashboardDrag(false); true }
                else -> external != null
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
        view.columnCount = if (activity.resources.configuration.screenWidthDp >= 600) 4 else 2
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
        if (!editing || isDragging || binder.isResizing) return false
        val holder = view.getChildViewHolder(wrapper) as? CardHolder ?: return false
        activity.editModeController.beginDashboardDrag(card, wrapper)
        dragging = card.id
        cancelled = false
        touch.startDrag(holder)
        return true
    }
    fun showExternal(card: DashboardCardType, span: Int, order: List<String>) {
        external = card.id
        overrides[card.id] = span
        cancelled = false
        lastExternalX = Float.NaN; lastExternalY = Float.NaN
        replace((items + card).distinct().sortedBy { order.indexOf(it.id) })
        recycler?.apply { scrollToPosition(items.indexOf(card) + 1); postOnAnimation(externalScroll) }
    }
    private fun updateExternalTarget(force: Boolean = false) {
        val view = recycler ?: return
        val id = external ?: return
        if (!force && !lastExternalX.isNaN() && abs(externalX - lastExternalX) < activity.dp(12) && abs(externalY - lastExternalY) < activity.dp(12)) return
        val children = (0 until view.childCount).map { view.getChildAt(it) }
            .filter { it.tag is String && DashboardCardType.fromId(it.tag as String) != null }
        if (children.any { it.tag == id && externalX >= it.left && externalX <= it.right && externalY >= it.top && externalY <= it.bottom }) return
        val target = children.filter { it.tag != id }.minByOrNull {
            val dx = (externalX - it.left - it.width / 2).toDouble()
            val dy = (externalY - it.top - it.height / 2).toDouble()
            dx * dx + dy * dy
        } ?: return
        val type = DashboardCardType.fromId(target.tag as String) ?: return
        val after = if (span(type) == view.columnCount) externalY > target.top + target.height / 2
            else (externalX > target.left + target.width / 2) != (view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        activity.editModeController.previewMoveDashboardCard(id, type.id, after)
        lastExternalX = externalX; lastExternalY = externalY
    }
    fun finishDrag(order: List<String>) {
        cancelNativeDrag()
        val added = external
        external = null
        recycler?.removeCallbacks(externalScroll)
        overrides.clear()
        val saved = order.mapNotNull(DashboardCardType::fromId).filter { it in items }
        replace(saved)
        if (added != null) items.indexOfFirst { it.id == added }.takeIf { it >= 0 }?.let { cards.notifyItemChanged(it, DATA) }
    }
    private fun cancelNativeDrag() {
        if (dragging == null) return
        dragging = null
        cancelled = true
        touch.attachToRecyclerView(null)
        recycler?.let(touch::attachToRecyclerView)
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
        override fun getItemViewType(position: Int) = items[position].ordinal + if (items[position].id == external) 1000 else 0
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): CardHolder {
            val card = DashboardCardType.entries[type % 1000]
            val wrapper = if (type >= 1000) FrameLayout(activity).apply {
                tag = card.id
                background = GradientDrawable().apply {
                    cornerRadius = activity.dp(28).toFloat()
                    setColor((activity.getColor(R.color.oneui_text_secondary) and 0x00ffffff) or 0x18000000)
                }
            } else binder.createRecyclerCard(card)
            wrapper.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(card.preferredHeightDp))
            return CardHolder(wrapper, card.id)
        }
        override fun onBindViewHolder(holder: CardHolder, position: Int) = onBindViewHolder(holder, position, mutableListOf(DATA))
        override fun onBindViewHolder(holder: CardHolder, position: Int, payloads: MutableList<Any>) {
            val card = items[position]
            holder.wrapper.setTag(R.id.dashboard_card_span, span(card))
            holder.wrapper.layoutParams.height = activity.dp(card.preferredHeightDp)
            if (card.id != external) binder.bindRecyclerCard(holder.wrapper, card, refreshContent = !holder.fresh && (payloads.isEmpty() || DATA in payloads || RESIZE in payloads || PROFILE in payloads),
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
