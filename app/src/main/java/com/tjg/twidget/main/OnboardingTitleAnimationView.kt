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
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.roundToInt

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
    @get:androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    private val usesHardwareBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    private val softwareLettersLayer = SoftwareTitleLayer(context, titlePaint)
    private val hardwareGlyphLayers = mutableListOf<HardwareGlyphLayer>()
    @get:androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private val blurEffects: Array<RenderEffect?> by lazy {
        arrayOfNulls(BLUR_BUCKET_COUNT + 1)
    }

    private var title = ""
    private var geometry = TitleGeometry.EMPTY
    private var letterAnimator: ValueAnimator? = null
    private var elapsedMs = -LETTER_STAGGER_MS
    var onAnimationFinished: (() -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
        val styled = context.obtainStyledAttributes(
            attrs,
            com.tjg.twidget.R.styleable.AnimatedTitleView,
        )
        title = styled.getText(com.tjg.twidget.R.styleable.AnimatedTitleView_android_text)?.toString().orEmpty()
        titlePaint.textSize = styled.getDimension(com.tjg.twidget.R.styleable.AnimatedTitleView_android_textSize, sp(32f))
        titlePaint.color = if (isDarkMode()) Color.WHITE else styled.getColor(com.tjg.twidget.R.styleable.AnimatedTitleView_android_textColor, titlePaint.color)
        styled.recycle()
        if (!usesHardwareBlur) {
            addView(
                softwareLettersLayer,
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        setTitle(title)
    }

    fun setTitle(value: CharSequence) {
        title = value.toString()
        requestLayout()
    }

    fun prepare() {
        cancel()
        elapsedMs = -LETTER_STAGGER_MS
        updateAnimationFrame()
    }

    fun play() {
        cancel()
        if (geometry.glyphs.isEmpty()) {
            if (title.isNotBlank()) post { play() }
            return
        }
        elapsedMs = 0f
        updateAnimationFrame()
        val finalStart = geometry.glyphs.last().sequence * LETTER_STAGGER_MS
        val duration = (finalStart + MOVEMENT_DURATION_MS).toLong()
        letterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            var cancelled = false
            this.duration = duration
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                elapsedMs = animator.currentPlayTime.toFloat()
                updateAnimationFrame()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    elapsedMs = duration.toFloat()
                    updateAnimationFrame()
                    onAnimationFinished?.invoke()
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
        // Blur needs drawing room, but must not add space before the next view.
        val layerHeight = measuredHeight + dp(TITLE_BLUR_BOTTOM_INSET_DP).toInt()
        val childHeightSpec = MeasureSpec.makeMeasureSpec(layerHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            getChildAt(index).layout(0, 0, width, height + dp(TITLE_BLUR_BOTTOM_INSET_DP).toInt())
        }
    }

    private fun rebuildGeometry(width: Int) {
        if (title.isBlank()) {
            setGeometry(TitleGeometry.EMPTY)
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
        setGeometry(
            TitleGeometry(
                title,
                glyphs,
                layout.height,
            ),
        )
    }

    private fun setGeometry(value: TitleGeometry) {
        if (geometry == value) return
        geometry = value
        if (usesHardwareBlur) {
            rebuildHardwareGlyphLayers()
        } else {
            softwareLettersLayer.geometry = geometry
        }
        updateAnimationFrame()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun rebuildHardwareGlyphLayers() {
        removeAllViews()
        hardwareGlyphLayers.clear()
        geometry.glyphs.forEach { glyph ->
            HardwareGlyphLayer(context, titlePaint, glyph).also { layer ->
                hardwareGlyphLayers += layer
                addView(
                    layer,
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            }
        }
    }

    private fun appliedBlurRadius(radius: Float): Float {
        if (radius <= BLUR_REMOVE_PX) return 0f
        if (radius < BLUR_FINE_THRESHOLD_PX) return radius
        if (radius < BLUR_COARSE_THRESHOLD_PX) return radius.roundToInt().toFloat()
        return (
            BLUR_COARSE_THRESHOLD_PX +
                ((radius - BLUR_COARSE_THRESHOLD_PX) / BLUR_COARSE_STEP_PX).roundToInt() *
                    BLUR_COARSE_STEP_PX
            ).roundToInt().coerceIn(0, BLUR_BUCKET_COUNT).toFloat()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun blurEffectFor(radius: Float): RenderEffect? {
        if (radius <= 0f) return null
        val bucket = radius.toInt()
        if (radius == bucket.toFloat() && bucket in 1..BLUR_BUCKET_COUNT) {
            return blurEffects[bucket] ?: RenderEffect.createBlurEffect(
                radius,
                radius,
                Shader.TileMode.DECAL,
            ).also { blurEffects[bucket] = it }
        }
        return RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
    }

    private fun updateAnimationFrame() {
        if (usesHardwareBlur) {
            val distance = (width * 0.16f).coerceAtMost(dp(80f))
            hardwareGlyphLayers.forEach { layer ->
                layer.updateAnimation(elapsedMs, distance)
            }
        } else {
            softwareLettersLayer.elapsedMs = elapsedMs
            softwareLettersLayer.invalidate()
        }
    }

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

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

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private inner class HardwareGlyphLayer(
        context: Context,
        paintSource: TextPaint,
        private val glyph: Glyph,
    ) : View(context) {
        private val paint = TextPaint(paintSource)
        private var lastAppliedRadius = Float.NaN

        fun updateAnimation(globalTime: Float, horizontalDistance: Float) {
            val localTime = globalTime - glyph.sequence * LETTER_STAGGER_MS
            if (localTime <= 0f) {
                alpha = 0f
                translationX = horizontalDistance
                updateBlur(0f)
                return
            }
            val fade = fadeInterpolator.getInterpolation(
                (localTime / FADE_DURATION_MS).coerceIn(0f, 1f),
            )
            val movement = movementInterpolator.getInterpolation(
                (localTime / MOVEMENT_DURATION_MS).coerceIn(0f, 1f),
            )
            alpha = fade
            translationX = horizontalDistance * (1f - movement)
            if (fade <= 0f) {
                updateBlur(0f)
            } else {
                updateBlur(GLYPH_BLUR_RADIUS_PX * (1f - movement))
            }
        }

        private fun updateBlur(radius: Float) {
            val applied = appliedBlurRadius(radius)
            if (applied == lastAppliedRadius) return
            lastAppliedRadius = applied
            setRenderEffect(blurEffectFor(applied))
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawText(title, glyph.start, glyph.end, glyph.x, glyph.baseline, paint)
        }
    }

    private inner class SoftwareTitleLayer(
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

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (geometry.glyphs.isEmpty()) return
            val horizontalDistance = (width * 0.16f).coerceAtMost(dp(80f))
            geometry.glyphs.forEach { glyph ->
                val localTime = elapsedMs - glyph.sequence * LETTER_STAGGER_MS
                if (localTime < 0f) return@forEach
                val fade = fadeInterpolator.getInterpolation(
                    (localTime / FADE_DURATION_MS).coerceIn(0f, 1f),
                )
                val movement = movementInterpolator.getInterpolation(
                    (localTime / MOVEMENT_DURATION_MS).coerceIn(0f, 1f),
                )
                paint.alpha = (fade * 255).toInt()
                val blurRadius = GLYPH_BLUR_RADIUS_PX * (1f - movement)
                paint.maskFilter = if (blurRadius > BLUR_REMOVE_PX) {
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
    }

    private companion object {
        const val FRAMES_PER_SECOND = 60f
        const val FRAMES_PER_LETTER = 7f
        const val LETTER_STAGGER_MS = FRAMES_PER_LETTER / FRAMES_PER_SECOND * 1_000f
        const val FADE_DURATION_MS = 500f
        const val MOVEMENT_DURATION_MS = 1_000L
        const val GLYPH_BLUR_RADIUS_PX = 100f
        const val BLUR_RADIUS_STEP_PX = 1f
        const val BLUR_BUCKET_COUNT = (GLYPH_BLUR_RADIUS_PX / BLUR_RADIUS_STEP_PX).toInt()
        const val BLUR_COARSE_THRESHOLD_PX = 70f
        const val BLUR_COARSE_STEP_PX = 4f
        const val BLUR_FINE_THRESHOLD_PX = 8f
        const val BLUR_REMOVE_PX = 0.05f
        const val TITLE_BLUR_BOTTOM_INSET_DP = 24f
    }
}
