package com.tjg.twidget.followers

import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.tjg.twidget.R
import com.tjg.twidget.ui.Sesl95SearchView

/** The native search overflow slot owns all optional list refinements. */
internal class FollowerSearchMenu(
    private val search: Sesl95SearchView,
    initial: FollowerFilters,
    private val changed: (FollowerFilters) -> Unit,
) {
    var filters = initial; private set
    var popup: PopupMenu? = null; private set
    init {
        search.seslSetOverflowMenuButtonIcon(search.context.getDrawable(R.drawable.ic_search_filters))
        search.seslSetOverflowMenuButtonVisibility(View.VISIBLE)
        search.seslSetOnOverflowMenuButtonClickListener { show() }
        updateIndicator()
    }
    fun show() {
        if (popup != null) return
        if (!search.isFloatingExpanded) search.expand(showKeyboard = false, animate = false)
        val anchor = search.seslGetOverflowMenuButton()
        // Wait for expansion/layout when invoked from the collapsed keyboard shortcut.
        afterSearchLayout { anchor.post {
            if (!search.isAttachedToWindow || !search.isFloatingExpanded || popup != null) return@post
            val menu = PopupMenu(search.context, anchor)
            fun heading(label: Int) { menu.menu.add(Menu.NONE, Menu.NONE, Menu.NONE, label).isEnabled = false }
            fun option(group: Int, id: Int, label: Int, selected: Boolean) {
                menu.menu.add(group, id, Menu.NONE, label).apply { isCheckable = true; isChecked = selected }
            }
            heading(R.string.floating_sort)
            option(1, R.id.follower_sort_count, R.string.floating_sort_count, filters.sort == FollowerSort.FOLLOWERS)
            option(1, R.id.follower_sort_name, R.string.floating_sort_name, filters.sort == FollowerSort.NAME)
            heading(R.string.floating_min_followers)
            option(2, R.id.follower_min_any, R.string.floating_all, filters.minimum == 0L)
            option(2, R.id.follower_min_10k, R.string.floating_10k, filters.minimum == 10_000L)
            option(2, R.id.follower_min_100k, R.string.floating_100k, filters.minimum == 100_000L)
            menu.menu.setGroupCheckable(1, true, true)
            menu.menu.setGroupCheckable(2, true, true)
            menu.menu.add(3, R.id.follower_filter_reset, Menu.NONE, R.string.floating_reset).isEnabled = filters.activeCount > 0
            menu.setOnMenuItemClickListener { item ->
                filters = when (item.itemId) {
                    R.id.follower_sort_count -> filters.copy(sort = FollowerSort.FOLLOWERS)
                    R.id.follower_sort_name -> filters.copy(sort = FollowerSort.NAME)
                    R.id.follower_min_any -> filters.copy(minimum = 0)
                    R.id.follower_min_10k -> filters.copy(minimum = 10_000)
                    R.id.follower_min_100k -> filters.copy(minimum = 100_000)
                    R.id.follower_filter_reset -> FollowerFilters()
                    else -> return@setOnMenuItemClickListener false
                }
                updateIndicator()
                changed(filters)
                true
            }
            menu.setOnDismissListener {
                popup = null
                search.onSearchMenuVisibilityChanged(false)
            }
            popup = menu
            search.onSearchMenuVisibilityChanged(true)
            menu.show()
        } }
    }
    private fun afterSearchLayout(action: () -> Unit) {
        if (search.isLaidOut && !search.isLayoutRequested) { action(); return }
        search.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int,
                oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                v.removeOnLayoutChangeListener(this)
                action()
            }
        })
    }
    fun dismiss() { popup?.dismiss() }
    private fun updateIndicator() {
        val context = search.context
        val description = if (filters.activeCount == 0) context.getString(R.string.floating_filters)
            else context.getString(R.string.floating_filters_active, filters.activeCount)
        search.seslGetOverflowMenuButton().apply {
            contentDescription = description
            androidx.appcompat.widget.TooltipCompat.setTooltipText(this, description)
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(
                if (filters.activeCount > 0) R.color.oneui_accent else R.color.oneui_text_primary))
        }
        search.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_button).apply {
            imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(
                if (filters.activeCount > 0) R.color.oneui_accent else R.color.oneui_text_primary))
            contentDescription = context.getString(R.string.top_followers_browser_search) +
                if (filters.activeCount > 0) ", $description" else ""
        }
    }
}
