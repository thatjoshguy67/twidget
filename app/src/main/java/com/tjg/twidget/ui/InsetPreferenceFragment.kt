package com.tjg.twidget.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.util.SeslRoundedCorner
import androidx.preference.Preference
import androidx.preference.SeslPreferenceCaption
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory

/**
 * Base for the app's preference screens. Each screen ends with an
 * oneui-design [InsetPreferenceCategory] that closes the last card with
 * rounded bottom corners — the same pattern the library's relative-links
 * helper uses — so the list never cuts off flush with the screen edge.
 */
abstract class InsetPreferenceFragment : PreferenceFragmentCompat() {
    /** Pages whose items own complete rounded surfaces expose the page in their gutters. */
    protected open val useSeparatePreferenceCards: Boolean get() = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The trailing inset draws the bottom rounding instead.
        listView.seslSetLastRoundedCorner(false)
        // SESL's transparent fade uses an unclipped layer on One UI. Preference
        // card backgrounds can leak through that layer below their rounded ends.
        // Fade into the same opaque surface as the preference container instead.
        val pageColor = android.util.TypedValue()
        requireContext().theme.resolveAttribute(androidx.appcompat.R.attr.roundedCornerColor, pageColor, true)
        listView.seslSetFadingEdgeColor(pageColor.data)
        // SESL also fills the navigation padding after drawing the fade. Match
        // the page surface so that padding cannot become a separate card strip.
        listView.seslSetFillBottomColor(pageColor.data)
        val bottomPadding = listView.paddingBottom
        val leftPadding = listView.paddingLeft
        val topPadding = listView.paddingTop
        val rightPadding = listView.paddingRight
        val cardBackground = if (useSeparatePreferenceCards) android.graphics.drawable.ColorDrawable(
            requireContext().getColor(com.tjg.twidget.R.color.oneui_bg)) else listView.background
        listView.clipToPadding = false
        // Keep the scrolling surface behind navigation, while allowing the last
        // row to scroll clear of gestures, buttons, and a bottom display cutout.
        // The activity handles IME height; do not add navigation padding twice.
        // Listen on the fragment root, leaving SESL's list fade listener intact.
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navigationPadding = (safe.bottom - ime.bottom).coerceAtLeast(0)
            // Preference rows share the list's card-coloured background. Keep
            // it beneath the rows, while exposing the page beneath navigation.
            listView.background = android.graphics.drawable.InsetDrawable(
                cardBackground, 0, 0, 0, bottomPadding + navigationPadding)
            listView.setPadding(leftPadding, topPadding,
                rightPadding, bottomPadding + navigationPadding)
            insets
        }
        ViewCompat.requestApplyInsets(view)
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

    /** Explanatory copy uses the library's page-coloured description below its card. */
    protected fun PreferenceScreen.addDescribedPreference(preference: Preference) {
        val description = preference.summary
        preference.summary = null
        addPreference(preference)
        if (!description.isNullOrBlank()) {
            addPreference(SeslPreferenceCaption(context).apply {
                key = "${preference.key}_description"
                title = description
                isPersistent = false
            })
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
