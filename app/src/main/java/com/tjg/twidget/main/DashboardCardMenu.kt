package com.tjg.twidget.main

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetFonts

/** Position once; changing focus after release must not move the menu. */
internal class DashboardCardMenu(private val activity: MainActivity) {
    private var popup: PopupWindow? = null
    private var account: String? = null

    fun show(card: DashboardCardType, anchor: View, pointerHeld: Boolean) {
        dismiss()
        if (!anchor.isAttachedToWindow) return
        val profile = activity.selectedAccount
        val expanded = card.span(activity, profile) == 2
        val actions = buildList<Pair<Int, () -> Unit>> {
            if (card.supportsSizeVariants) add((if (expanded) R.string.dashboard_size_shrink else R.string.dashboard_size_enlarge) to {
                if ((card.span(activity, profile) == 2) == expanded) activity.editModeController.resizeDashboardCard(card)
            })
            add(R.string.dashboard_remove_card to { activity.editModeController.removeDashboardCard(card.id) })
        }
        val labels = actions.map { activity.getString(it.first) }
        val adapter = object : ArrayAdapter<String>(activity, R.layout.dropdown_item_checked, R.id.dropdown_label, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).apply {
                    findViewById<ImageView>(R.id.dropdown_check).visibility = View.GONE
                    findViewById<TextView>(R.id.dropdown_label).apply {
                        typeface = TwidgetFonts.forApp(activity, 400)
                        setTextColor(activity.getColor(if (actions[position].first == R.string.dashboard_remove_card)
                            R.color.metric_red else R.color.oneui_text_primary))
                    }
                }
        }
        val list = ListView(activity, null, android.R.attr.dropDownListViewStyle).apply {
            this.adapter = adapter
            divider = null
            isVerticalScrollBarEnabled = false
        }
        val window = PopupWindow(activity, null, android.R.attr.listPopupWindowStyle).apply {
            contentView = list
            isFocusable = !pointerHeld
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = activity.dp(8).toFloat()
        }
        val decor = activity.window.decorView
        val frame = Rect().also(decor::getWindowVisibleDisplayFrame)
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val gap = activity.dp(12)
        window.width = activity.dp(220).coerceAtMost((frame.width() - gap * 2).coerceAtLeast(activity.dp(120)))
        val padding = Rect().also { window.background?.getPadding(it) }
        val rowWidth = (window.width - padding.left - padding.right).coerceAtLeast(1)
        val contentHeight = (0 until adapter.count).sumOf { index ->
            adapter.getView(index, null, list).apply {
                measure(View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            }.measuredHeight
        }
        window.height = (contentHeight + list.paddingTop + list.paddingBottom + padding.top + padding.bottom)
            .coerceAtMost((frame.height() - gap * 2).coerceAtLeast(activity.dp(48)))
        val left = (location[0] + anchor.width / 2 - window.width / 2)
            .coerceIn(frame.left + gap, (frame.right - window.width - gap).coerceAtLeast(frame.left + gap))
        val below = location[1] + anchor.height + activity.dp(4)
        val top = (if (below + window.height <= frame.bottom - gap) below else location[1] - window.height - activity.dp(4))
            .coerceIn(frame.top + gap, (frame.bottom - window.height - gap).coerceAtLeast(frame.top + gap))
        val detached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { window.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detached)
        window.setOnDismissListener {
            anchor.removeOnAttachStateChangeListener(detached)
            anchor.setTag(R.id.dashboard_card_held, false)
            DashboardCardMotion.release(anchor)
            if (popup === window) { popup = null; account = null }
        }
        list.setOnItemClickListener { _, _, index, _ ->
            window.dismiss()
            if (activity.selectedAccount == profile && activity.editModeController.editMode &&
                card.id in activity.dashboardBinder.cardIds()) actions[index].second()
        }
        popup = window
        account = profile
        window.showAtLocation(decor, Gravity.TOP or Gravity.LEFT, left - origin[0], top - origin[1])
        TwidgetFonts.observeWindow(list.rootView)
    }
    fun release() {
        popup?.takeIf { it.isShowing }?.apply {
            isFocusable = true
            update() // Only window flags change; no anchor lookup or remeasurement.
        }
    }
    fun validate(profile: String, editing: Boolean) {
        if (!editing || account?.let { it != profile } == true) dismiss()
    }
    fun dismissIfShowing(): Boolean {
        if (popup?.isShowing != true) return false
        dismiss()
        return true
    }
    fun dismiss() { popup?.dismiss(); popup = null; account = null }
}
