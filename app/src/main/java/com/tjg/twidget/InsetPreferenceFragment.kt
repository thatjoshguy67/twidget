package com.tjg.twidget

import android.os.Bundle
import android.view.View
import androidx.appcompat.util.SeslRoundedCorner
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
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
        val baseBottomPadding = listView.paddingBottom
        listView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(listView) { list, insets ->
            val navigationBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            list.setPadding(
                list.paddingLeft,
                list.paddingTop,
                list.paddingRight,
                baseBottomPadding + navigationBar,
            )
            insets
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
