package com.tjg.twidget.followers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import com.tjg.twidget.ui.SeslRimDrawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1:1 port of Reminder's SearchFabTransition (b0/c0/u/y):
 * damped-spring pill morph in ViewGroupOverlay with 55ms press-dip/rebound,
 * native hardware RenderNode elevation shadow matching the rest views, and
 * clipped icon/content cross-fade.
 */
internal object SearchFabMorphTransition {

    private class SpringCurve(stiffness: Float, private val dampingRatio: Float) {
        private val omega = sqrt(stiffness.toDouble())
        private val omegaD = omega * sqrt(max(0.0, 1.0 - dampingRatio * dampingRatio))
        val durationMs: Long

        init {
            var settled = 600L
            var t = 16L
            while (t <= 2000L) {
                val v = evaluate(t.toFloat())
                val next = evaluate((t + 16L).toFloat())
                if (abs(1f - v) < 0.001f && abs(next - v) < 1e-4f) {
                    settled = t
                    break
                }
                t += 16L
            }
            durationMs = settled
        }

        fun evaluate(timeMs: Float): Float {
            val t = timeMs / 1000.0
            val decay = exp(-dampingRatio * omega * t)
            val osc = if (dampingRatio < 1f) {
                (sin(omegaD * t) * ((dampingRatio * omega) / omegaD)) + cos(omegaD * t)
            } else {
                (omega * t) + 1.0
            }
            return (1.0 - decay * osc).toFloat()
        }

        fun sample(timeMs: Float): Float = if (timeMs >= durationMs) 1f else evaluate(timeMs)
    }

    private class MorphConfig(deltaWidthDp: Float) {
        private val ratio = (deltaWidthDp / 100f).coerceIn(1f, 2.5f)
        val widthSpring = SpringCurve(650f / sqrt(ratio), (0.65f + (ratio - 1f) * 0.15f).coerceAtMost(1f))
        val heightSpring = SpringCurve(650f, 0.65f)
        val positionSpring = SpringCurve(420f, 1.0f)
        private val dipSpring = SpringCurve(1200f, 1.0f)
        private val reboundSpring = SpringCurve(420f, 1.0f)
        val fadeInterpolator = PathInterpolator(0.22f, 0.25f, 0f, 1f)
        val totalDurationMs: Long = maxOf(
            widthSpring.durationMs,
            heightSpring.durationMs,
            positionSpring.durationMs,
            reboundSpring.durationMs + 55L,
        )

        fun scaleFactor(timeMs: Float): Float {
            val clamped = timeMs.coerceAtMost(55f)
            val dip = dipSpring.sample(clamped) * -0.120000005f + 1f
            if (timeMs < 55f) return dip
            val elapsed = timeMs - 55f
            if (elapsed >= reboundSpring.durationMs) return 1f
            val dipOmega = sqrt(1200.0)
            val t55 = 0.055
            val velocity55 = (exp(-dipOmega * t55) * dipOmega * dipOmega * t55).toFloat() * -0.120000005f
            val t = elapsed / 1000.0
            val offset = (dip - 1f).toDouble()
            val rebOmega = sqrt(420.0)
            return (exp(-rebOmega * t) * (((rebOmega * offset + velocity55) * t) + offset) + 1.0).toFloat()
        }
    }

    private class MorphDrawable(
        host: ViewGroup,
        private val fromView: View,
        private val toView: View,
        private val translationView: View,
        private val fromRect: RectF,
        private val toRect: RectF,
        private val fromElevation: Float,
        private val toElevation: Float,
        pillColor: Int,
        plateBackground: Drawable?,
        private val config: MorphConfig,
        private val isRtl: Boolean,
    ) : Drawable() {
        var elapsedMs: Float = 0f
        var currentElevation: Float = fromElevation
            private set
        private val currentRect = RectF()
        private val clipPath = Path()
        private val pillDrawable: Drawable = plateBackground ?: GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(pillColor)
        }
        private val rimDrawable = SeslRimDrawable(
            width = host.resources.displayMetrics.density,
            fallbackRadius = host.resources.displayMetrics.density * 26f,
            light = (host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
                Configuration.UI_MODE_NIGHT_YES,
            background = { pillDrawable },
        )

        fun updateRect(): Float {
            val wFrom = fromRect.width()
            val w = wFrom + (toRect.width() - wFrom) * config.widthSpring.sample(elapsedMs)
            val hFrom = fromRect.height()
            val h = hFrom + (toRect.height() - hFrom) * config.heightSpring.sample(elapsedMs)
            val pos = config.positionSpring.sample(elapsedMs)
            val left = if (isRtl) {
                fromRect.left + (toRect.left - fromRect.left) * pos
            } else {
                (fromRect.right + (toRect.right - fromRect.right) * pos) - w
            }
            val baseCenterY = fromRect.centerY() + (toRect.centerY() - fromRect.centerY()) * pos
            val top = (baseCenterY - h / 2f) + translationView.translationY
            currentRect.set(left, top, left + w, top + h)
            currentElevation = fromElevation + (toElevation - fromElevation) * pos
            return currentElevation
        }

        override fun draw(canvas: Canvas) {
            updateRect()
            val save = canvas.save()
            val scale = config.scaleFactor(elapsedMs)
            canvas.scale(scale, scale, currentRect.centerX(), currentRect.centerY())
            clipPath.reset()
            val radius = currentRect.height() / 2f
            clipPath.addRoundRect(currentRect, radius, radius, Path.Direction.CW)

            pillDrawable.setBounds(
                currentRect.left.toInt(),
                currentRect.top.toInt(),
                currentRect.right.toInt(),
                currentRect.bottom.toInt(),
            )
            (pillDrawable as? GradientDrawable)?.cornerRadius = radius
            pillDrawable.draw(canvas)
            rimDrawable.bounds = pillDrawable.bounds
            rimDrawable.draw(canvas)

            canvas.clipPath(clipPath)
            val outAlpha = 1f - config.fadeInterpolator.getInterpolation((elapsedMs / 30f).coerceIn(0f, 1f))
            val inAlpha = config.fadeInterpolator.getInterpolation(((elapsedMs - 50f) / 100f).coerceIn(0f, 1f))
            drawContent(canvas, fromView, fromRect, outAlpha)
            drawContent(canvas, toView, toRect, inAlpha)
            canvas.restoreToCount(save)
        }

        private fun drawContent(canvas: Canvas, view: View, targetRect: RectF, alpha: Float) {
            if (alpha <= 0f || view.width <= 0 || view.height <= 0) return
            val layer = canvas.saveLayerAlpha(currentRect, (alpha * 255).toInt())
            canvas.translate(currentRect.centerX(), currentRect.centerY())
            canvas.scale(targetRect.width() / view.width, targetRect.height() / view.height)
            canvas.translate(-view.width / 2f, -view.height / 2f)
            if (view is ImageView) {
                canvas.translate(view.paddingLeft.toFloat(), view.paddingTop.toFloat())
                canvas.concat(view.imageMatrix)
                view.drawable?.draw(canvas)
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    if (child.visibility == View.VISIBLE) {
                        val s = canvas.save()
                        canvas.translate(child.left.toFloat(), child.top.toFloat())
                        child.draw(canvas)
                        canvas.restoreToCount(s)
                    }
                }
            } else {
                view.draw(canvas)
            }
            canvas.restoreToCount(layer)
        }

        override fun getOutline(outline: Outline) {
            val scale = config.scaleFactor(elapsedMs)
            val halfW = (currentRect.width() * scale) / 2f
            val halfH = (currentRect.height() * scale) / 2f
            outline.setRoundRect(
                (currentRect.centerX() - halfW).toInt(),
                (currentRect.centerY() - halfH).toInt(),
                (currentRect.centerX() + halfW).toInt(),
                (currentRect.centerY() + halfH).toInt(),
                halfH,
            )
            outline.alpha = 1f
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun start(
        host: ViewGroup,
        fromView: View,
        toView: View,
        translationView: View,
        entering: Boolean,
        fromRect: RectF,
        toRect: RectF,
        fromElevation: Float,
        toElevation: Float,
        pillColor: Int,
        plateBackgroundSource: View? = null,
        onEnd: () -> Unit,
    ): ValueAnimator {
        val density = host.resources.displayMetrics.density
        val restFrom = RectF(fromRect)
        val restTo = RectF(toRect)
        if (entering) {
            restTo.offset(0f, -translationView.translationY)
        } else {
            restFrom.offset(0f, -translationView.translationY)
        }
        val config = MorphConfig(abs(restTo.width() - restFrom.width()) / density)
        val clonedBackground = plateBackgroundSource?.background?.constantState
            ?.newDrawable(host.resources)?.mutate()
        val drawable = MorphDrawable(
            host = host,
            fromView = fromView,
            toView = toView,
            translationView = translationView,
            fromRect = restFrom,
            toRect = restTo,
            fromElevation = fromElevation,
            toElevation = toElevation,
            pillColor = pillColor,
            plateBackground = clonedBackground,
            config = config,
            isRtl = host.layoutDirection == View.LAYOUT_DIRECTION_RTL,
        )
        drawable.updateRect()
        val morphView = View(host.context).apply {
            background = drawable
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = drawable.currentElevation
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    drawable.getOutline(outline)
                }
            }
            layout(0, 0, host.width, host.height)
        }
        host.overlay.add(morphView)
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = config.totalDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                drawable.elapsedMs = anim.animatedFraction * config.totalDurationMs
                morphView.elevation = drawable.updateRect()
                morphView.invalidateOutline()
                morphView.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private fun cleanup() {
                    host.overlay.remove(morphView)
                }

                override fun onAnimationEnd(animation: Animator) {
                    cleanup()
                    onEnd()
                }

                override fun onAnimationCancel(animation: Animator) {
                    cleanup()
                }
            })
            start()
        }
    }
}
