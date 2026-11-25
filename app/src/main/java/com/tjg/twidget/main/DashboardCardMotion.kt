package com.tjg.twidget.main

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.sin
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.tjg.twidget.R

/** Mode changes ease from the current pose; pickup retains its responsive spring. */
internal object DashboardCardMotion {
    private val easing = PathInterpolator(.4f, 0f, .2f, 1f)
    fun applyBase(view: View, editing: Boolean) {
        val changed = view.getTag(R.id.dashboard_motion_editing) != editing
        view.setTag(R.id.dashboard_motion_editing, editing)
        if (view.getTag(R.id.dashboard_card_held) == true || view.getTag(R.id.dashboard_drag_height) is Int) return
        if (changed) transition(view, editing)
        else if (editing && view.getTag(R.id.dashboard_jiggle) == null) transition(view, true)
        else if (!editing && view.getTag(R.id.dashboard_mode_transition) == null &&
            (view.scaleX != 1f || view.scaleY != 1f || view.rotation != 0f)) transition(view, false)
    }
    fun lift(view: View) {
        cancelTransition(view)
        stopJiggle(view)
        scale(view, 1f)
    }
    fun release(view: View) {
        if (view.getTag(R.id.dashboard_card_held) == true || view.getTag(R.id.dashboard_drag_height) is Int) return
        transition(view, view.getTag(R.id.dashboard_motion_editing) == true)
    }
    private fun transition(view: View, editing: Boolean) {
        cancelTransition(view)
        cancelSprings(view)
        val target = if (editing) .97f else 1f
        if (!view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            stopJiggle(view)
            view.scaleX = target; view.scaleY = target
            view.setTag(R.id.dashboard_jiggle_amount, if (editing) 1f else 0f)
            return
        }
        val startX = view.scaleX
        val startY = view.scaleY
        val startAmount = view.getTag(R.id.dashboard_jiggle_amount) as? Float ?: 0f
        if (editing) jiggle(view)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = easing
            addUpdateListener {
                val progress = it.animatedValue as Float
                view.scaleX = startX + (target - startX) * progress
                view.scaleY = startY + (target - startY) * progress
                view.setTag(R.id.dashboard_jiggle_amount, startAmount + ((if (editing) 1f else 0f) - startAmount) * progress)
                if (progress == 1f && !editing) stopJiggle(view)
            }
        }
        view.setTag(R.id.dashboard_mode_transition, animator)
        animator.start()
    }
    private fun jiggle(view: View) {
        if (!view.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            stopJiggle(view)
            return
        }
        if (view.getTag(R.id.dashboard_jiggle) is ValueAnimator) return
        val seed = (view.tag?.hashCode() ?: view.id).toLong() and 0x7fffffff
        val phase = (seed % 360) * Math.PI / 180.0
        val widthDp = view.width / view.resources.displayMetrics.density
        val amplitude = if (widthDp > 240) .32f else .5f
        val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 440L + seed % 91
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val amount = view.getTag(R.id.dashboard_jiggle_amount) as? Float ?: 0f
                view.rotation = sin((it.animatedValue as Float) + phase).toFloat() * amplitude * amount
            }
        }
        view.setTag(R.id.dashboard_jiggle, animator)
        animator.start()
    }
    private fun stopJiggle(view: View) {
        (view.getTag(R.id.dashboard_jiggle) as? ValueAnimator)?.cancel()
        view.setTag(R.id.dashboard_jiggle, null)
        view.setTag(R.id.dashboard_jiggle_amount, 0f)
        view.rotation = 0f
    }
    private fun scale(view: View, target: Float) {
        if (!ValueAnimator.areAnimatorsEnabled() || !view.isAttachedToWindow) {
            cancel(view); view.scaleX = target; view.scaleY = target
            return
        }
        for ((id, property) in listOf(R.id.dashboard_spring_x to DynamicAnimation.SCALE_X,
            R.id.dashboard_spring_y to DynamicAnimation.SCALE_Y)) {
            val spring = view.getTag(id) as? SpringAnimation ?: SpringAnimation(view, property).apply {
                this.spring = SpringForce().setStiffness(1500f).setDampingRatio(.75f)
                view.setTag(id, this)
            }
            spring.animateToFinalPosition(target)
        }
    }
    private fun cancelTransition(view: View) {
        (view.getTag(R.id.dashboard_mode_transition) as? ValueAnimator)?.apply { removeAllUpdateListeners(); cancel() }
        view.setTag(R.id.dashboard_mode_transition, null)
    }
    private fun cancelSprings(view: View) {
        for (id in listOf(R.id.dashboard_spring_x, R.id.dashboard_spring_y))
            (view.getTag(id) as? SpringAnimation)?.cancel()
    }
    fun cancel(view: View) {
        cancelTransition(view)
        stopJiggle(view)
        cancelSprings(view)
    }
}
