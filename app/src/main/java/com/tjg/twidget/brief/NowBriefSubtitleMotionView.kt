package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.os.Build
import android.text.Layout
import android.util.AttributeSet
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.pow
import kotlin.math.roundToInt

class NowBriefSubtitleMotionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val spans = mutableListOf<MotionCharacterSpan>()
    private var animator: ValueAnimator? = null
    private var preparedText = ""
    private var profile = SUBTITLE_PROFILE
    private var renderMotion = false
    private var previousLayerType: Int? = null

    fun prepareNowBriefMotion() {
        prepareMotion(SUBTITLE_PROFILE)
    }

    fun prepareNowBriefTitleMotion() {
        prepareMotion(TITLE_PROFILE)
    }

    fun prepareNowBriefOnboardingTitleMotion() {
        prepareMotion(ONBOARDING_TITLE_PROFILE)
    }

    private fun prepareMotion(motionProfile: MotionProfile) {
        animator?.cancel()
        profile = motionProfile
        val source = text?.toString().orEmpty()
        preparedText = source
        if (source.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            clearMotion()
            return
        }
        restoreMotionLayer()
        // Two linear gradients in ComposeShader need a software layer on API 26–27.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P && profile.colors != null) {
            previousLayerType = layerType
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        spans.clear()
        val gradientStartX = paddingLeft.toFloat()
        val gradientEndX = (width - paddingRight).toFloat()
            .takeIf { it > gradientStartX }
            ?: gradientStartX + paint.measureText(source)
        val gradientStartY = paddingTop.toFloat()
        val gradientEndY = (height - paddingBottom).toFloat()
            .takeIf { it > gradientStartY }
            ?: gradientStartY + textSize
        var sequence = 0
        var index = 0
        while (index < source.length) {
            val codePoint = source.codePointAt(index)
            val end = index + Character.charCount(codePoint)
            if (!Character.isWhitespace(codePoint)) {
                MotionCharacterSpan(
                    start = index,
                    end = end,
                    startDelayMs = sequence * profile.characterIntervalMs,
                    blurRadiusPx = dp(profile.maxBlurDp),
                    startScale = profile.startScale,
                    colors = profile.colors,
                    gradientStartX = gradientStartX,
                    gradientEndX = gradientEndX,
                    gradientStartY = gradientStartY,
                    gradientEndY = gradientEndY,
                ).also { span ->
                    spans += span
                }
            }
            sequence++
            index = end
        }
        renderMotion = true
        alpha = 1f
        translationY = 0f
        invalidate()
    }

    fun startNowBriefMotion(startDelayMs: Long) {
        startMotion(startDelayMs)
    }

    fun startNowBriefTitleMotion(
        startDelayMs: Long,
        onEnd: (() -> Unit)? = null,
        onBeforeEnd: (() -> Unit)? = null,
        beforeEndLeadMs: Long = 0L,
    ) {
        startMotion(startDelayMs, onEnd, onBeforeEnd, beforeEndLeadMs)
    }

    private fun startMotion(
        startDelayMs: Long,
        onEnd: (() -> Unit)? = null,
        onBeforeEnd: (() -> Unit)? = null,
        beforeEndLeadMs: Long = 0L,
    ) {
        animator?.cancel()
        if (spans.isEmpty() || !ValueAnimator.areAnimatorsEnabled()) {
            clearMotion()
            onBeforeEnd?.invoke()
            onEnd?.invoke()
            return
        }
        val characterRevealDuration = spans.maxOf { it.startDelayMs } + profile.characterDurationMs
        val textRevealStart = (characterRevealDuration - profile.textRevealLeadInMs).coerceAtLeast(0L)
        val shaderFlowDuration = maxOf(
            profile.shaderFlowDurationMs ?: 0L,
            characterRevealDuration + if (profile.colors != null) 180L else 0L,
        )
        val textRevealDuration = if (profile.colors != null) {
            profile.textRevealDurationMs ?: shaderFlowDuration
        } else {
            0L
        }
        val totalDuration = maxOf(
            textRevealStart + textRevealDuration,
            shaderFlowDuration,
        )
        var beforeEndStarted = false
        val beforeEndRunnable = Runnable {
            beforeEndStarted = true
            onBeforeEnd?.invoke()
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalDuration
            startDelay = startDelayMs
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animation ->
                val elapsed = animation.currentPlayTime
                val shaderProgress = if (profile.shaderFlowDurationMs != null) {
                    SHADER_INTERPOLATOR.getInterpolation((elapsed.toFloat() / shaderFlowDuration).coerceIn(0f, 1f))
                } else {
                    0f
                }
                val textRevealProgress = profile.colors
                    ?.let {
                        val revealProgress = ((elapsed - textRevealStart).toFloat() / textRevealDuration)
                            .coerceIn(0f, 1f)
                        TEXT_REVEAL_INTERPOLATOR.getInterpolation(revealProgress)
                    }
                    ?: 0f
                spans.forEach {
                    it.setElapsed(
                        elapsed,
                        profile.characterDurationMs,
                        shaderProgress,
                        textRevealProgress,
                    )
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                    removeCallbacks(beforeEndRunnable)
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    removeCallbacks(beforeEndRunnable)
                    if (!cancelled) {
                        if (!beforeEndStarted) onBeforeEnd?.invoke()
                        renderMotion = false
                        restoreMotionLayer()
                        invalidate()
                        onEnd?.invoke()
                    }
                }
            })
            start()
        }
        if (onBeforeEnd != null) {
            postDelayed(
                beforeEndRunnable,
                startDelayMs + (totalDuration - beforeEndLeadMs).coerceAtLeast(0L),
            )
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        renderMotion = false
        restoreMotionLayer()
        super.onDetachedFromWindow()
    }

    private fun clearMotion() {
        animator?.cancel()
        spans.forEach { it.finish() }
        renderMotion = false
        restoreMotionLayer()
        if (text?.toString() != preparedText) {
            text = preparedText
        }
        alpha = 1f
        translationY = 0f
        invalidate()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun restoreMotionLayer() {
        previousLayerType?.let { setLayerType(it, null) }
        previousLayerType = null
    }

    override fun onDraw(canvas: Canvas) {
        if (!renderMotion) {
            super.onDraw(canvas)
            return
        }
        val currentLayout = layout ?: return
        val currentText = text ?: return
        val save = canvas.save()
        canvas.translate(
            (compoundPaddingLeft - scrollX).toFloat(),
            (extendedPaddingTop - scrollY).toFloat(),
        )
        val savedColor = paint.color
        val savedShader = paint.shader
        // TextView normally resolves its ColorStateList in onDraw. Custom motion
        // bypasses that path, so resolve it here too (including dark/disabled states).
        val textColor = currentTextColor
        paint.color = textColor
        val motionShader = spans.firstOrNull()?.colorShader(textColor)
        try {
            if (spans.all { it.isFullyVisible() }) {
                paint.shader = motionShader
                currentLayout.draw(canvas)
            } else {
                spans.forEach { span ->
                    val line = currentLayout.getLineForOffset(span.start)
                    span.drawGlyph(
                        canvas,
                        currentText,
                        currentLayout.getPrimaryHorizontal(span.start),
                        currentLayout.getLineBaseline(line),
                        currentLayout.getLineStart(line),
                        currentLayout.getLineEnd(line),
                        currentLayout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT,
                        paint,
                        motionShader,
                    )
                }
            }
        } finally {
            paint.color = savedColor
            paint.shader = savedShader
            canvas.restoreToCount(save)
        }
    }

    private class MotionCharacterSpan(
        val start: Int,
        val end: Int,
        val startDelayMs: Long,
        private val blurRadiusPx: Float,
        private val startScale: Float,
        private val colors: IntArray?,
        private val gradientStartX: Float,
        private val gradientEndX: Float,
        private val gradientStartY: Float,
        private val gradientEndY: Float,
    ) {
        private var progress = 0f
        private var shaderProgress = 0f
        private var textRevealProgress = 0f

        fun setElapsed(
            elapsedMs: Long,
            durationMs: Long,
            newShaderProgress: Float,
            newTextRevealProgress: Float,
        ) {
            val raw = ((elapsedMs - startDelayMs).toFloat() / durationMs)
                .coerceIn(0f, 1f)
            progress = APPEAR_INTERPOLATOR.getInterpolation(raw)
            shaderProgress = newShaderProgress
            textRevealProgress = newTextRevealProgress
        }

        fun finish() {
            progress = 1f
        }

        fun isFullyVisible(): Boolean = progress >= 1f

        fun drawGlyph(
            canvas: Canvas,
            text: CharSequence,
            x: Float,
            y: Int,
            contextStart: Int,
            contextEnd: Int,
            isRtl: Boolean,
            paint: Paint,
            motionShader: Shader?,
        ) {
            val savedAlpha = paint.alpha
            val savedMaskFilter = paint.maskFilter
            val savedShader = paint.shader
            val glyphWidth = paint.measureText(text, start, end)
            val scale = startScale + (1f - startScale) * progress
            val blur = blurRadiusPx * (1f - progress)
            val motionMaskFilter = if (blur > 0.5f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            canvas.save()
            canvas.scale(scale, scale, x + glyphWidth / 2f, y.toFloat())
            paint.shader = motionShader ?: savedShader
            paint.maskFilter = motionMaskFilter
            paint.alpha = (savedAlpha * progress).roundToInt()
            canvas.drawTextRun(
                text,
                start,
                end,
                contextStart,
                contextEnd,
                x,
                y.toFloat(),
                isRtl,
                paint,
            )
            canvas.restore()
            paint.alpha = savedAlpha
            paint.maskFilter = savedMaskFilter
            paint.shader = savedShader
        }

        fun colorShader(textColor: Int): Shader? {
            val titleColors = colors ?: return null
            val phase = (gradientEndX - gradientStartX) * (shaderProgress - 0.5f)
            val blue = blueShader(titleColors, phase)
            // Blend the colors before drawing the glyph once. Drawing the glyph
            // twice leaves blue antialiased edges that disappear at the final frame.
            return if (textRevealProgress > 0f) {
                ComposeShader(blue, textSweepShader(textColor), PorterDuff.Mode.SRC_OVER)
            } else blue
        }

        private fun blueShader(colors: IntArray, phase: Float): Shader = LinearGradient(
            gradientStartX + phase,
            gradientStartY + phase,
            gradientEndX + phase,
            gradientEndY + phase,
            colors,
            null,
            Shader.TileMode.CLAMP,
        )

        private fun textSweepShader(textColor: Int): Shader {
            val gradientWidth = gradientEndX - gradientStartX
            val sweepTravelProgress = textRevealProgress.toDouble().pow(0.7).toFloat()
            val sweepOffset = gradientWidth * (sweepTravelProgress * 2f - 1f)
            val diagonalEndY = gradientStartY + gradientWidth
            return LinearGradient(
                gradientStartX + sweepOffset,
                gradientStartY + sweepOffset,
                gradientEndX + sweepOffset,
                diagonalEndY + sweepOffset,
                intArrayOf(
                    textColor,
                    withAlpha(textColor, 0.85f),
                    withAlpha(textColor, 0.55f),
                    withAlpha(textColor, 0.24f),
                    withAlpha(textColor, 0f),
                ),
                floatArrayOf(0f, 0.18f, 0.42f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private companion object {
        fun withAlpha(color: Int, fraction: Float): Int = Color.argb(
            (Color.alpha(color) * fraction).roundToInt(),
            Color.red(color), Color.green(color), Color.blue(color),
        )

        data class MotionProfile(
            val characterDurationMs: Long,
            val characterIntervalMs: Long,
            val maxBlurDp: Float,
            val startScale: Float,
            val colors: IntArray? = null,
            val shaderFlowDurationMs: Long? = null,
            val textRevealLeadInMs: Long = 0L,
            val textRevealDurationMs: Long? = null,
        )

        val SUBTITLE_PROFILE = MotionProfile(200L, 10L, 4f, 0.85f)
        val TITLE_PROFILE = MotionProfile(
            characterDurationMs = 600L,
            characterIntervalMs = 45L,
            maxBlurDp = 7f,
            startScale = 0.85f,
            colors = intArrayOf(0xFFB8EEFF.toInt(), 0xFF78CFFF.toInt(), 0xFF3F93FF.toInt(), 0xFFB8EEFF.toInt()),
            shaderFlowDurationMs = 1020L,
            textRevealLeadInMs = 600L,
            textRevealDurationMs = 4000L,
        )
        val ONBOARDING_TITLE_PROFILE = MotionProfile(
            characterDurationMs = 280L,
            characterIntervalMs = 24L,
            maxBlurDp = 7f,
            startScale = 0.85f,
            colors = intArrayOf(0xFFB8EEFF.toInt(), 0xFF78CFFF.toInt(), 0xFF3F93FF.toInt(), 0xFFB8EEFF.toInt()),
            shaderFlowDurationMs = 500L,
            textRevealLeadInMs = 300L,
            textRevealDurationMs = 1500L,
        )
        val APPEAR_INTERPOLATOR = PathInterpolator(0.33f, 1f, 0.68f, 1f)
        val SHADER_INTERPOLATOR = PathInterpolator(0.61f, 1f, 0.88f, 1f)
        val TEXT_REVEAL_INTERPOLATOR = PathInterpolator(0.35f, 0f, 0.65f, 1f)
    }
}
