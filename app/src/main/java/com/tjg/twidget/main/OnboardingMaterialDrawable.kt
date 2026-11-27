package com.tjg.twidget.main

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.animation.PathInterpolator
import com.tjg.twidget.brief.BriefBlueBackground
import com.tjg.twidget.brief.BriefBlueTheme

/** One continuous Brief material. Stage changes morph its soft aperture instead of swapping images. */
internal class OnboardingMaterialDrawable(resources: Resources, private val night: Boolean) : Drawable() {
    enum class Stage { WELCOME, PERMISSIONS, HANDLE, CONFIRM, AI, WIDGET, DONE }
    private data class Pose(val x: Float, val y: Float, val width: Float, val height: Float,
        val phase: Float, val light: Float) {
        fun towards(other: Pose, t: Float) = Pose(mix(x, other.x, t), mix(y, other.y, t),
            mix(width, other.width, t), mix(height, other.height, t),
            mix(phase, other.phase, t), mix(light, other.light, t))
        companion object { private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t }
    }
    private val lightBackdrop = androidx.core.content.res.ResourcesCompat.getColor(
        resources, androidx.appcompat.R.color.sesl_background_color_light, null)
    // A gentle blue wash joins the moving pools without the old grey veil or white gaps.
    private val lightTop = androidx.core.graphics.ColorUtils.blendARGB(lightBackdrop, 0xFF91B5E3.toInt(), .28f)
    private val lightBottom = androidx.core.graphics.ColorUtils.blendARGB(lightBackdrop, 0xFFBDD0ED.toInt(), .18f)
    private val material = BriefBlueBackground(BriefBlueTheme.AFTERNOON, night,
        density = resources.displayMetrics.density,
        paletteOverride = if (night) null else listOf(lightTop, lightBottom,
            0xFF3498EF.toInt(), 0xFF34BDD0.toInt(), 0xFF6B8CE4.toInt()),
        lightPoolRadiusScale = if (night) 1f else 1.35f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val mask = RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
        floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private var base: Shader? = null
    private var pose = Pose(.5f, .32f, .04f, .02f, 0f, 0f)
    private var stage = Stage.WELCOME
    private var transition: ValueAnimator? = null
    private var drift: ValueAnimator? = null
    private var ambient = 0f
    private var opacity = 255
    private var filter: ColorFilter? = null
    private var paused = false

    private fun resting(stage: Stage) = when (stage) {
        Stage.WELCOME -> Pose(.58f, .16f, 1.15f, .88f, .16f, .88f)
        Stage.PERMISSIONS -> Pose(.18f, .24f, 1.12f, .82f, .27f, .76f)
        Stage.HANDLE -> Pose(.86f, .22f, 1.08f, .92f, .43f, .82f)
        Stage.CONFIRM -> Pose(.68f, .36f, 1.22f, .96f, .5f, .85f)
        Stage.AI -> Pose(.26f, .3f, 1.35f, 1.04f, .57f, .9f)
        Stage.WIDGET -> Pose(.2f, .52f, 1.4f, 1.03f, .61f, .92f)
        Stage.DONE -> Pose(.5f, -.16f, .95f, .38f, .8f, .48f)
    }

    fun show(next: Stage, animate: Boolean) {
        stage = next
        animate(resting(next), if (next == Stage.DONE) 1800L else 1100L, animate)
    }

    /** Starts on the same frame as the title: open, overshoot gently, then settle. */
    fun revealWelcome() {
        stage = Stage.WELCOME
        val from = pose
        val crest = Pose(.48f, .35f, 1.55f, 1.28f, .09f, 1f)
        val target = resting(stage)
        transition?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { pose = target; invalidateSelf(); return }
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2900L
            interpolator = PathInterpolator(.22f, 0f, .18f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val local = if (t < .7f) t / .7f else (t - .7f) / .3f
                val eased = local * local * (3f - 2f * local)
                pose = if (t < .7f) from.towards(crest, eased) else crest.towards(target, eased)
                invalidateSelf()
            }
            start()
            if (paused) pause()
        }
    }

    private fun animate(target: Pose, durationMs: Long, enabled: Boolean) {
        transition?.cancel()
        val from = pose
        if (!enabled || !ValueAnimator.areAnimatorsEnabled()) { pose = target; invalidateSelf(); return }
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = PathInterpolator(.32f, 0f, .12f, 1f)
            addUpdateListener { pose = from.towards(target, it.animatedValue as Float); invalidateSelf() }
            start()
            if (paused) pause()
        }
    }

    fun resumeMotion() {
        paused = false
        if (!ValueAnimator.areAnimatorsEnabled()) {
            transition?.cancel(); pose = resting(stage); invalidateSelf(); return
        }
        transition?.resume()
        if (drift == null) drift = ValueAnimator.ofFloat(ambient, ambient + 1f).apply {
            duration = 24000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { ambient = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    fun pauseMotion() { paused = true; transition?.pause(); drift?.cancel(); drift = null }
    fun dispose() { transition?.cancel(); transition = null; drift?.cancel(); drift = null }

    override fun onBoundsChange(bounds: Rect) {
        material.bounds = Rect(0, 0, bounds.width(), bounds.height())
        base = LinearGradient(0f, 0f, 0f, bounds.height().toFloat(),
            if (night) 0xFF071426.toInt() else lightTop,
            if (night) 0xFF080E19.toInt() else lightBottom, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        paint.shader = base; paint.alpha = opacity; paint.colorFilter = filter
        canvas.drawRect(0f, 0f, w, h, paint)
        // The aperture is feathered, so the reveal and retract have no hard clip edge.
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        material.setMotionProgress(pose.phase + kotlin.math.sin(ambient * Math.PI * 2).toFloat() * .025f)
        material.alpha = (opacity * pose.light).toInt()
        material.colorFilter = filter
        material.draw(canvas)
        matrix.setScale(w * pose.width, h * pose.height)
        matrix.postTranslate(w * pose.x, h * pose.y)
        mask.setLocalMatrix(matrix); maskPaint.shader = mask
        canvas.drawRect(0f, 0f, w, h, maskPaint)
        canvas.restoreToCount(layer)
        // Keep the dark material subdued; light mode uses its blue-white backdrop directly.
        if (night) {
            paint.shader = null; paint.color = 0xFF09264B.toInt(); paint.alpha = (opacity * .12f).toInt()
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
