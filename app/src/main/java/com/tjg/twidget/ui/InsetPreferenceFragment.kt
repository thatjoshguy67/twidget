package com.tjg.twidget.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.util.SeslRoundedCorner
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory

/**
 * Base for the app's preference screens. Each screen ends with an
 * oneui-design [InsetPreferenceCategory] that closes the last card with
 * rounded bottom corners — the same pattern the library's relative-links
 * helper uses — so the list never cuts off flush with the screen edge.
 */
abstract class InsetPreferenceFragment : PreferenceFragmentCompat() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The trailing inset draws the bottom rounding instead.
        listView.seslSetLastRoundedCorner(false)
        listView.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    TwidgetFonts.applyTo(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
        for (index in 0 until listView.childCount) {
            TwidgetFonts.applyTo(listView.getChildAt(index))
        }
    }

    /** Call after the last preference is added, before assigning the screen. */
    protected fun PreferenceScreen.addBottomInset() {
        addPreference(
            InsetPreferenceCategory(context).apply {
                key = "bottom_inset"
                seslSetSubheaderRoundedBackground(
                    SeslRoundedCorner.ROUNDED_CORNER_BOTTOM_LEFT or
                        SeslRoundedCorner.ROUNDED_CORNER_BOTTOM_RIGHT
                )
            }
        )
    }
}
