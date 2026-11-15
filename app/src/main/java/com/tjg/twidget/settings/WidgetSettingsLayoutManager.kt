package com.tjg.twidget.settings

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.ui.TwidgetFonts

/** Wide non-One UI windows get a catalogue grid; phones and popovers remain a list. */
internal class WidgetSettingsLayoutManager(private val context: Context) : GridLayoutManager(context, 1) {
    override fun onMeasure(recycler: RecyclerView.Recycler, state: RecyclerView.State, widthSpec: Int, heightSpec: Int) {
        val available = (android.view.View.MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight) /
            context.resources.displayMetrics.density
        val config = context.resources.configuration
        val columns = columns(available.toInt(), config.smallestScreenWidthDp, TwidgetFonts.hasSystemOneUiSans, config.fontScale)
        if (spanCount != columns) spanCount = columns
        super.onMeasure(recycler, state, widthSpec, heightSpec)
    }
    override fun supportsPredictiveItemAnimations() = false

    companion object {
        internal fun columns(widthDp: Int, smallestWidthDp: Int, oneUi: Boolean, fontScale: Float): Int {
            if (oneUi || smallestWidthDp < 600) return 1
            val minimum = (320 * fontScale.coerceAtLeast(1f)).toInt()
            return (widthDp / minimum).coerceIn(1, 3)
        }
    }
}
