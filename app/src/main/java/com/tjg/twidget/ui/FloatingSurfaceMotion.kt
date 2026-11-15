package com.tjg.twidget.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Retarget a running spring instead of restarting it on rapid reversals. */
internal class FloatingSurfaceMotion(initial: Float, minimumChange: Float = .001f, private val update: (Float) -> Unit) {
    private var value = initial
    private val spring = SpringAnimation(FloatValueHolder(initial)).apply {
        spring = SpringForce(initial).setDampingRatio(.85f).setStiffness(420f)
        setMinimumVisibleChange(minimumChange)
        addUpdateListener { _, next, _ -> value = next; update(next) }
    }
    fun moveTo(target: Float, animate: Boolean = true) {
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            spring.cancel()
            value = target
            update(target)
        } else {
            if (!spring.isRunning) spring.setStartValue(value)
            spring.animateToFinalPosition(target)
        }
    }
    fun cancel() = spring.cancel()
}

/** Animated layout space, so expanding controls never paint outside their hit bounds. */
internal class FloatingRevealLayout(context: Context) : FrameLayout(context) {
    private var fraction = 0f
    private var expanded = false
    private val motion = FloatingSurfaceMotion(0f) {
        fraction = it.coerceIn(0f, 1f)
        alpha = fraction
        requestLayout()
    }
    init { alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    fun reveal(show: Boolean, animate: Boolean = true) {
        expanded = show
        descendantFocusability = if (show) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = if (show) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        motion.moveTo(if (show) 1f else 0f, animate && isLaidOut)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, (measuredHeight * fraction).toInt())
    }
    override fun onDetachedFromWindow() {
        motion.moveTo(if (expanded) 1f else 0f, false)
        super.onDetachedFromWindow()
    }
}
