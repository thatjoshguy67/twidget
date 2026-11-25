package com.tjg.twidget.ui

import android.content.Context
import android.text.TextUtils
import androidx.appcompat.widget.AppCompatTextView

/** Keep complete tweet lines inside the preview; preserve the full text for accessibility. */
internal class DashboardFadingTextView(context: Context) : AppCompatTextView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Re-evaluate after resizing, font changes and a recycled card's new text.
        maxLines = Int.MAX_VALUE
        ellipsize = null
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val textLayout = layout ?: return
        val available = measuredHeight - compoundPaddingTop - compoundPaddingBottom
        val completeLines = (0 until textLayout.lineCount).takeWhile {
            textLayout.getLineBottom(it) <= available
        }.size
        if (completeLines < textLayout.lineCount) {
            maxLines = completeLines.coerceAtLeast(1)
            ellipsize = TextUtils.TruncateAt.END
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
