package com.tjg.twidget.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

class OnboardingTitleAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val fadeInterpolator = PathInterpolator(0.333f, 0f, 0.667f, 1f)
    private val movementInterpolator = PathInterpolator(0.22f, 0.25f, 0f, 1f)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sec", Typeface.BOLD)
    }
    private val lettersLayer = TitleLayer(context, titlePaint)

    private var title = ""
    private var geometry = TitleGeometry.EMPTY
    private var letterAnimator: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        val styled = context.obtainStyledAttributes(
            attrs,
            intArrayOf(android.R.attr.text, android.R.attr.textSize, android.R.attr.textColor),
        )
        title = styled.getText(0)?.toString().orEmpty()
        titlePaint.textSize = styled.getDimension(1, sp(32f))
        titlePaint.color = if (isDarkMode()) Color.WHITE else styled.getColor(2, titlePaint.color)
        styled.recycle()
        lettersLayer.updatePaint(titlePaint)
        addView(lettersLayer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setTitle(title)
    }

    fun setTitle(value: CharSequence) {
        title = value.toString()
        requestLayout()
    }

    fun prepare() {
        cancel()
        lettersLayer.elapsedMs = -LETTER_STAGGER_MS
        lettersLayer.alpha = 1f
        lettersLayer.invalidate()
    }

    fun play() {
        cancel()
        if (geometry.glyphs.isEmpty()) {
            if (title.isNotBlank()) post { play() }
            return
        }
        lettersLayer.elapsedMs = 0f
        lettersLayer.alpha = 1f
        val finalStart = (geometry.glyphs.last().sequence * LETTER_STAGGER_MS).toLong()
        val duration = finalStart + MOVEMENT_DURATION_MS
        letterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            var cancelled = false
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                lettersLayer.elapsedMs = animator.currentPlayTime.toFloat()
                lettersLayer.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    lettersLayer.elapsedMs = duration.toFloat()
                    lettersLayer.invalidate()
                }
            })
            start()
        }
    }

    fun cancel() {
        letterAnimator?.cancel()
        letterAnimator = null
    }

    override fun onDetachedFromWindow() {
        cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        rebuildGeometry(availableWidth)
        val desiredHeight = geometry.height.coerceAtLeast(1)
        val measuredWidth = resolveSize(availableWidth, widthMeasureSpec)
        val measuredHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        lettersLayer.measure(childWidthSpec, childHeightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        lettersLayer.layout(0, 0, width, height)
    }

    private fun rebuildGeometry(width: Int) {
        if (title.isBlank()) {
            geometry = TitleGeometry.EMPTY
            lettersLayer.geometry = geometry
            return
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
        var sequence = 0
        val glyphs = buildList {
            var offset = 0
            while (offset < title.length) {
                val next = offset + Character.charCount(title.codePointAt(offset))
                if (!title.substring(offset, next).all(Char::isWhitespace)) {
                    val line = layout.getLineForOffset(offset)
                    add(
                        Glyph(
                            start = offset,
                            end = next,
                            x = layout.getPrimaryHorizontal(offset),
                            baseline = layout.getLineBaseline(line).toFloat(),
                            sequence = sequence++,
                        ),
                    )
                }
                offset = next
            }
        }
        geometry = TitleGeometry(title, glyphs, layout.height)
        lettersLayer.geometry = geometry
    }

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private fun isDarkMode(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private data class Glyph(
        val start: Int,
        val end: Int,
        val x: Float,
        val baseline: Float,
        val sequence: Int,
    )

    private data class TitleGeometry(
        val text: String,
        val glyphs: List<Glyph>,
        val height: Int,
    ) {
        companion object {
            val EMPTY = TitleGeometry("", emptyList(), 0)
        }
    }

    private inner class TitleLayer(
        context: Context,
        paintSource: TextPaint,
    ) : View(context) {
        private val paint = TextPaint(paintSource)
        var geometry: TitleGeometry = TitleGeometry.EMPTY
            set(value) {
                field = value
                invalidate()
            }
        var elapsedMs = 0f

        fun updatePaint(source: TextPaint) {
            paint.set(source)
            invalidate()
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (geometry.glyphs.isEmpty()) return
            val horizontalDistance = (width * 0.16f).coerceAtMost(dp(80f))
            geometry.glyphs.forEach { glyph ->
                val localTime = elapsedMs - glyph.sequence * LETTER_STAGGER_MS
                if (localTime < 0f) return@forEach
                val alpha = fadeInterpolator.getInterpolation(
                    (localTime / FADE_DURATION_MS).coerceIn(0f, 1f),
                )
                val movement = movementInterpolator.getInterpolation(
                    (localTime / MOVEMENT_DURATION_MS).coerceIn(0f, 1f),
                )
                paint.alpha = (alpha * 255).toInt()
                val blurRadius = (GLYPH_BLUR_RADIUS_PX * (1f - movement)).coerceAtLeast(0f)
                paint.maskFilter = if (blurRadius > 0.5f) {
                    BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
                } else {
                    null
                }
                canvas.drawText(
                    geometry.text,
                    glyph.start,
                    glyph.end,
                    glyph.x + horizontalDistance * (1f - movement),
                    glyph.baseline,
                    paint,
                )
            }
            paint.maskFilter = null
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
    }

    private companion object {
        const val FRAMES_PER_SECOND = 60f
        const val FRAMES_PER_LETTER = 7f
        const val LETTER_STAGGER_MS = FRAMES_PER_LETTER / FRAMES_PER_SECOND * 1_000f
        const val FADE_DURATION_MS = 500f
        const val MOVEMENT_DURATION_MS = 1_000L
        const val GLYPH_BLUR_RADIUS_PX = 100f
    }
}
