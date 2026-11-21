package com.tjg.twidget.ui

import android.content.Context
import android.os.Build
import android.text.StaticLayout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.ceil

/**
 * TextView that shrink-wraps its measured width to the widest rendered line even when wrapping
 * across multiple lines, so a horizontal parent group can center icon + multi-line text together.
 * When [candidateTexts] is provided, the widest line across all candidates is used so rotating
 * status messages keep a stable horizontal center.
 */
class ShrinkWrapTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var candidateTexts: List<CharSequence> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val textLayout = layout ?: return
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        if (widthMode == MeasureSpec.EXACTLY) return
        if (textLayout.lineCount <= 1 && candidateTexts.isEmpty()) return

        var maxLineWidth = 0f
        for (i in 0 until textLayout.lineCount) {
            val lineWidth = textLayout.getLineWidth(i)
            if (lineWidth > maxLineWidth) maxLineWidth = lineWidth
        }

        if (candidateTexts.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val padding = compoundPaddingLeft + compoundPaddingRight
            val avail = if (widthMode == MeasureSpec.UNSPECIFIED) {
                Int.MAX_VALUE / 2
            } else {
                (MeasureSpec.getSize(widthMeasureSpec) - padding).coerceAtLeast(1)
            }
            val limit = if (maxLines > 0) maxLines else Int.MAX_VALUE
            for (candidate in candidateTexts) {
                val candidateLayout = StaticLayout.Builder
                    .obtain(candidate, 0, candidate.length, paint, avail)
                    .setMaxLines(limit)
                    .build()
                for (i in 0 until candidateLayout.lineCount) {
                    val lineWidth = candidateLayout.getLineWidth(i)
                    if (lineWidth > maxLineWidth) maxLineWidth = lineWidth
                }
            }
        }

        var desiredWidth = (ceil(maxLineWidth) + 1f + compoundPaddingLeft + compoundPaddingRight).toInt()
        if (widthMode == MeasureSpec.AT_MOST) {
            desiredWidth = desiredWidth.coerceAtMost(MeasureSpec.getSize(widthMeasureSpec))
        }
        if (desiredWidth > 0 && desiredWidth != measuredWidth) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
                heightMeasureSpec,
            )
        }
    }
}
