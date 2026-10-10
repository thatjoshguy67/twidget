package com.tjg.twidget.ui

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** SESL creates its two overlay labels directly and exposes no typography setter. */
internal object SeslFastScrollerTypography {
    private val scrollerField = RecyclerView::class.java.getDeclaredField("mFastScroller").apply { isAccessible = true }

    fun setPreviewRank(list: RecyclerView, rank: Int) {
        val scroller = scrollerField.get(list) ?: return
        for (name in listOf("mPrimaryText", "mSecondaryText")) {
            val label = scroller.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(scroller) as TextView
            val text = rank.toString()
            if (label.text.toString() != text) label.text = text
        }
    }

    fun applyTo(list: RecyclerView) {
        val scroller = scrollerField.get(list) ?: return
        val face = TwidgetFonts.forApp(list.context, 400)
        for (name in listOf("mPrimaryText", "mSecondaryText")) {
            val label = scroller.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(scroller) as TextView
            label.typeface = face
        }
    }
}
