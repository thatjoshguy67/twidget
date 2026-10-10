package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatViewInflater

/** Includes AppCompat dialogs and popup menus, which have their own window roots. */
@Keep
class TwidgetViewInflater : AppCompatViewInflater() {
    private fun <T : View> T.withAppFont(): T = apply {
        // Popup menus measure detached rows before showing their window. Set the
        // final font now so their content height includes any wrapped titles.
        TwidgetFonts.applyTo(this)
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                TwidgetFonts.observeWindow(view.rootView)
                TwidgetFonts.applyTo(view)
            }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }

    override fun createTextView(context: Context, attrs: AttributeSet) =
        super.createTextView(context, attrs).withAppFont()
    override fun createButton(context: Context, attrs: AttributeSet) =
        super.createButton(context, attrs).withAppFont()
    override fun createEditText(context: Context, attrs: AttributeSet) =
        super.createEditText(context, attrs).withAppFont()
    override fun createCheckedTextView(context: Context, attrs: AttributeSet) =
        super.createCheckedTextView(context, attrs).withAppFont()
    override fun createCheckBox(context: Context, attrs: AttributeSet) =
        super.createCheckBox(context, attrs).withAppFont()
    override fun createRadioButton(context: Context, attrs: AttributeSet) =
        super.createRadioButton(context, attrs).withAppFont()
    override fun createToggleButton(context: Context, attrs: AttributeSet) =
        super.createToggleButton(context, attrs).withAppFont()
    override fun createAutoCompleteTextView(context: Context, attrs: AttributeSet) =
        super.createAutoCompleteTextView(context, attrs).withAppFont()
    override fun createMultiAutoCompleteTextView(context: Context, attrs: AttributeSet) =
        super.createMultiAutoCompleteTextView(context, attrs).withAppFont()
}
