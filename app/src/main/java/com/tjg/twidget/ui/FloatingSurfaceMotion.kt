package com.tjg.twidget.ui

import android.animation.ValueAnimator
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
