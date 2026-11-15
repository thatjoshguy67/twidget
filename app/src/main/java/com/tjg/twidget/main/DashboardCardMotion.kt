package com.tjg.twidget.main

import android.animation.ValueAnimator
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.tjg.twidget.R

/** One retained spring per axis, so pickup and release can reverse without restarting. */
internal object DashboardCardMotion {
    fun applyBase(view: View, editing: Boolean) {
        val changed = view.getTag(R.id.dashboard_motion_editing) != editing
        view.setTag(R.id.dashboard_motion_editing, editing)
        if (view.getTag(R.id.dashboard_card_held) == true || view.getTag(R.id.dashboard_drag_height) is Int) return
        if (changed) cancel(view)
        if (changed || !running(view)) {
            view.scaleX = if (editing) .97f else 1f
            view.scaleY = view.scaleX
        }
    }
    fun lift(view: View) = scale(view, 1f)
    fun release(view: View) {
        if (view.getTag(R.id.dashboard_card_held) == true || view.getTag(R.id.dashboard_drag_height) is Int) return
        scale(view, if (view.getTag(R.id.dashboard_motion_editing) == true) .97f else 1f)
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
    private fun running(view: View) = listOf(R.id.dashboard_spring_x, R.id.dashboard_spring_y)
        .any { (view.getTag(it) as? SpringAnimation)?.isRunning == true }
    fun cancel(view: View) {
        for (id in listOf(R.id.dashboard_spring_x, R.id.dashboard_spring_y))
            (view.getTag(id) as? SpringAnimation)?.cancel()
    }
}
