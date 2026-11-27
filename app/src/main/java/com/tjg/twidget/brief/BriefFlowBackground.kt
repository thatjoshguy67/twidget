package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.animation.PathInterpolator

/** Shared Brief material with continuous stage shifts and softly blended outcome light. */
internal class BriefFlowBackground(resources: Resources, theme: BriefBlueTheme, private val night: Boolean,
    private val phaseOffset: Float = .1f) : Drawable() {
    enum class State { NORMAL, CONFIRM, LOADING, SUCCESS, FAILURE }
    private data class Pose(val phase: Float, val success: Float, val failure: Float) {
        fun towards(other: Pose, t: Float) = Pose(phase + (other.phase - phase) * t,
            success + (other.success - success) * t, failure + (other.failure - failure) * t)
    }
    private val material = BriefBlueBackground(theme, night, density = resources.displayMetrics.density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var successGlow: Shader? = null
    private var failureGlow: Shader? = null
    private var pose = Pose(0f, 0f, 0f)
    private var target = pose
    private var transition: ValueAnimator? = null
    private var drift: ValueAnimator? = null
    private var entrance: ValueAnimator? = null
    private var entry = 0f
    private var phase = BriefBlueBackground.motionPhase()
    private var paused = true
    private var opacity = 255
    private var filter: ColorFilter? = null

    fun show(state: State, animate: Boolean = true) {
        val next = when (state) {
            State.NORMAL -> Pose(0f, 0f, 0f)
            State.CONFIRM -> Pose(.06f, 0f, 0f)
            State.LOADING -> Pose(.13f, 0f, 0f)
            State.SUCCESS -> Pose(.24f, 1f, 0f)
            State.FAILURE -> Pose(-.12f, 0f, 1f)
        }
        if (next == target) return
        target = next
        transition?.cancel()
        val from = pose
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            pose = target; invalidateSelf(); return
        }
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (state == State.SUCCESS || state == State.FAILURE) 1200L else 850L
            interpolator = PathInterpolator(.25f, 0f, .12f, 1f)
            addUpdateListener { pose = from.towards(next, it.animatedValue as Float); invalidateSelf() }
            start()
            if (paused) pause()
        }
    }

    fun resumeMotion() {
        paused = false
        if (!ValueAnimator.areAnimatorsEnabled()) {
            transition?.cancel(); entrance?.cancel(); drift?.cancel(); drift = null
            pose = target; entry = 1f; invalidateSelf(); return
        }
        transition?.resume()
        if (entrance != null) entrance?.resume() else if (entry < 1f) {
            entrance = ValueAnimator.ofFloat(entry, 1f).apply {
                duration = (1200L * (1f - entry)).toLong().coerceAtLeast(1L)
                interpolator = PathInterpolator(.22f, 0f, .12f, 1f)
                addUpdateListener { entry = it.animatedValue as Float; invalidateSelf() }
                start()
            }
        }
        if (drift == null) drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BriefBlueBackground.MOTION_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = BriefBlueBackground.motionPhase(); invalidateSelf() }
            start()
        }
    }

    fun pauseMotion() {
        paused = true; transition?.pause(); entrance?.pause(); drift?.cancel(); drift = null
    }

    fun dispose() {
        transition?.cancel(); entrance?.cancel(); drift?.cancel()
        transition = null; entrance = null; drift = null
    }

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        material.setBounds(0, 0, bounds.width(), bounds.height())
        fun glow(x: Float, y: Float, color: Int) = RadialGradient(w * x, h * y, maxOf(w * .9f, h * .7f),
            intArrayOf(color, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        successGlow = glow(.78f, .6f, if (night) 0xFF32D4A0.toInt() else 0xFF36CAA7.toInt())
        failureGlow = glow(.18f, .38f, if (night) 0xFFF080A8.toInt() else 0xFFED88A0.toInt())
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val layer = if (opacity < 255) canvas.saveLayerAlpha(0f, 0f,
            bounds.width().toFloat(), bounds.height().toFloat(), opacity) else -1
        material.setMotionProgress(phase + phaseOffset + pose.phase)
        material.setEntranceProgress(entry)
        material.colorFilter = filter
        material.draw(canvas)
        paint.colorFilter = filter
        fun light(shader: Shader?, strength: Float) {
            if (strength <= 0f) return
            paint.shader = shader
            paint.alpha = (255 * .45f * strength).toInt()
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), paint)
        }
        light(successGlow, pose.success)
        light(failureGlow, pose.failure)
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
