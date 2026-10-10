package com.tjg.twidget.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat

/** Keeps the numeral height while narrowing Google Sans Flex to the card's available width. */
class CardValueTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var preferredSizeSp = 60f
    private var preferredWidth = 124
    var fittedWidth: Int = preferredWidth
        private set

    init {
        TextViewCompat.setAutoSizeTextTypeWithDefaults(this, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
        maxLines = 1
        includeFontPadding = false
    }

    fun configure(sizeSp: Float, width: Int) {
        preferredSizeSp = sizeSp
        preferredWidth = width
        requestLayout()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        // TextView can otherwise just invalidate when its fixed bounds do not change.
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        fit(measuredWidth - compoundPaddingLeft - compoundPaddingRight,
            measuredHeight - compoundPaddingTop - compoundPaddingBottom)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fit(availableWidth: Int, availableHeight: Int) {
        if (availableWidth <= 0 || availableHeight <= 0) return
        textScaleX = 1f
        setTextSize(TypedValue.COMPLEX_UNIT_SP, preferredSizeSp)
        val value = text.toString()
        // Preserve the normal width unless the value crowds the card's edges.
        val widthLimit = availableWidth * 0.95f
        // Width axes are discrete to share cached typefaces and avoid per-frame font allocations.
        var low = 25
        var high = preferredWidth
        var best = low
        while (low <= high) {
            val middle = (low + high) / 2
            typeface = TwidgetFonts.cardValueTypeface(context, middle)
            if (paint.measureText(value) <= widthLimit) {
                best = middle
                low = middle + 1
            } else high = middle - 1
        }
        fittedWidth = best
        typeface = TwidgetFonts.cardValueTypeface(context, best)
        // Extreme values and large accessibility text may exhaust the width axis.
        // Only then reduce height; retain the complete value even for unusually long numbers.
        val bounds = paint.fontMetrics
        val height = bounds.descent - bounds.ascent
        val width = paint.measureText(value)
        val scale = minOf(1f, widthLimit / width.coerceAtLeast(1f),
            availableHeight / height.coerceAtLeast(1f))
        if (scale < 1f) setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * scale)
    }
}
