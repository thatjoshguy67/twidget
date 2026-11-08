package com.tjg.twidget.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.tjg.twidget.R

/** Compact selectors and action menus share the widget editor's dropdown style. */
internal fun showSettingsDropdown(anchor: View, labels: List<String>, selectedIndex: Int = -1, onSelected: (Int) -> Unit): ListPopupWindow? {
    if (!anchor.isAttachedToWindow || labels.isEmpty()) return null
    val context = anchor.context
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val accent = context.getColor(R.color.oneui_accent)
    val normal = context.getColor(R.color.oneui_text_primary)
    val adapter = object : ArrayAdapter<String>(context, R.layout.dropdown_item_checked, R.id.dropdown_label, labels) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).apply {
                val selected = position == selectedIndex
                findViewById<TextView>(R.id.dropdown_label).apply {
                    setTextColor(if (selected) accent else normal)
                    typeface = Typeface.create("sec", if (selected) Typeface.BOLD else Typeface.NORMAL)
                }
                findViewById<ImageView>(R.id.dropdown_check).apply {
                    imageTintList = ColorStateList.valueOf(accent)
                    visibility = if (selected) View.VISIBLE else if (selectedIndex < 0) View.GONE else View.INVISIBLE
                }
            }
    }
    val paint = android.text.TextPaint().apply {
        textSize = 16 * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sec", Typeface.BOLD)
    }
    val popup = ListPopupWindow(context).apply {
        setAdapter(adapter)
        anchorView = anchor
        width = maxOf(dp(220), labels.maxOf { paint.measureText(it).toInt() } + dp(72))
            .coerceAtMost((anchor.rootView.width - dp(32)).coerceAtLeast(dp(120)))
        height = ListPopupWindow.WRAP_CONTENT
        isModal = true
        horizontalOffset = dp(18)
        verticalOffset = -dp(8)
    }
    val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { popup.dismiss() }
    }
    anchor.addOnAttachStateChangeListener(detachListener)
    popup.setOnDismissListener { anchor.removeOnAttachStateChangeListener(detachListener) }
    popup.setOnItemClickListener { _, _, position, _ ->
        popup.dismiss()
        onSelected(position)
    }
    popup.show()
    popup.listView?.let { com.tjg.twidget.ui.TwidgetFonts.observeWindow(it.rootView) }
    if (selectedIndex >= 0) popup.setSelection(selectedIndex)
    return popup
}

internal open class SettingsListPreference(context: Context) : ListPreference(context) {
    private var anchor: View? = null
    private var popup: ListPopupWindow? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        popup?.dismiss()
        anchor = holder.itemView
        holder.itemView.setOnLongClickListener { onClick(); true }
    }

    override fun onClick() {
        val row = anchor ?: return
        val labels = entries?.map { it.toString() } ?: return
        val values = entryValues ?: return
        if (labels.size != values.size) return
        popup?.dismiss()
        popup = showSettingsDropdown(row, labels, findIndexOfValue(value)) { index ->
            val next = values[index].toString()
            if (callChangeListener(next)) value = next
        }
    }
}
