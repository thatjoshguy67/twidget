package com.tjg.twidget.followers

import android.view.View
import androidx.core.view.ViewCompat
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import dev.oneuiproject.oneui.R as OneUiIconR
import com.tjg.twidget.R
import com.tjg.twidget.ui.Sesl95SearchView

/** The native search overflow slot owns all optional list refinements. */
internal class FollowerSearchMenu(
    private val search: Sesl95SearchView,
    initial: FollowerFilters,
    private val changed: (FollowerFilters) -> Unit,
) {
    var filters = initial; private set
    var dialog: AlertDialog? = null; private set
    init {
        search.seslSetOverflowMenuButtonIcon(search.context.getDrawable(OneUiIconR.drawable.ic_oui_list_filter))
        search.seslSetOverflowMenuButtonVisibility(View.VISIBLE)
        search.seslSetOnOverflowMenuButtonClickListener { show() }
        updateIndicator()
    }
    fun show() {
        if (dialog != null || !search.isAttachedToWindow) return
        if (!search.isFloatingExpanded) search.expand(showKeyboard = false, animate = false)
        val builder = AlertDialog.Builder(search.context)
        val context = builder.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun group(label: Int, options: List<Pair<Int, Int>>, selected: Int): RadioGroup {
            content.addView(AppCompatTextView(context).apply {
                setText(label)
                setTextAppearance(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead)
                setPadding(0, dp(12), 0, dp(4))
                ViewCompat.setAccessibilityHeading(this, true)
            })
            return RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                options.forEach { (optionId, labelId) ->
                    addView(AppCompatRadioButton(context).apply {
                        id = optionId
                        setText(labelId)
                        minHeight = dp(48)
                    }, RadioGroup.LayoutParams(-1, -2))
                }
                check(selected)
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val sort = group(R.string.floating_sort, listOf(
            R.id.follower_sort_count to R.string.floating_sort_count,
            R.id.follower_sort_name to R.string.floating_sort_name),
            if (filters.sort == FollowerSort.NAME) R.id.follower_sort_name else R.id.follower_sort_count)
        val minimum = group(R.string.floating_min_followers, listOf(
            R.id.follower_min_any to R.string.floating_all,
            R.id.follower_min_10k to R.string.floating_10k,
            R.id.follower_min_100k to R.string.floating_100k), when (filters.minimum) {
                100_000L -> R.id.follower_min_100k
                10_000L -> R.id.follower_min_10k
                else -> R.id.follower_min_any
            })
        val window = builder.setTitle(R.string.floating_filters)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                filters = FollowerFilters(
                    if (sort.checkedRadioButtonId == R.id.follower_sort_name) FollowerSort.NAME else FollowerSort.FOLLOWERS,
                    when (minimum.checkedRadioButtonId) {
                        R.id.follower_min_100k -> 100_000L
                        R.id.follower_min_10k -> 10_000L
                        else -> 0L
                    })
                updateIndicator()
                changed(filters)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.floating_reset, null)
            .create()
        window.setOnDismissListener {
            if (dialog === window) {
                dialog = null
                search.onSearchMenuVisibilityChanged(false)
            }
        }
        dialog = window
        // Dialog focus may hide the IME; keep the in-list search and its query alive.
        search.onSearchMenuVisibilityChanged(true)
        window.show()
        window.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            sort.check(R.id.follower_sort_count)
            minimum.check(R.id.follower_min_any)
        }
    }
    fun dismiss() {
        val current = dialog ?: return
        dialog = null
        search.onSearchMenuVisibilityChanged(false)
        current.dismiss()
    }
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
