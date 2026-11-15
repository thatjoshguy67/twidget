package com.tjg.twidget.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.R
import kotlin.math.roundToInt

/** Native item movement; a size change reveals a single, already-measured content state. */
internal class DashboardItemAnimator : DefaultItemAnimator() {
    private val sizes = mutableMapOf<RecyclerView.ViewHolder, ValueAnimator>()
    init {
        supportsChangeAnimations = false
        moveDuration = 180L
        addDuration = 120L
        removeDuration = 120L
    }
    override fun animatePersistence(holder: RecyclerView.ViewHolder, before: ItemHolderInfo, after: ItemHolderInfo): Boolean =
        if (differentSize(before, after)) animateSize(holder, before, after) else super.animatePersistence(holder, before, after)
    override fun animateChange(old: RecyclerView.ViewHolder, fresh: RecyclerView.ViewHolder,
        before: ItemHolderInfo, after: ItemHolderInfo): Boolean =
        if (old === fresh && differentSize(before, after)) animateSize(fresh, before, after)
        else super.animateChange(old, fresh, before, after)
    private fun differentSize(a: ItemHolderInfo, b: ItemHolderInfo) =
        a.right - a.left != b.right - b.left || a.bottom - a.top != b.bottom - b.top

    private fun animateSize(holder: RecyclerView.ViewHolder, before: ItemHolderInfo, after: ItemHolderInfo): Boolean {
        endAnimation(holder)
        val view = holder.itemView
        val endWidth = after.right - after.left
        val endHeight = after.bottom - after.top
        view.setTag(R.id.dashboard_resize_animating, true)
        fun frame(fraction: Float) {
            val width = ((before.right - before.left) + (endWidth - before.right + before.left) * fraction).roundToInt()
            val height = ((before.bottom - before.top) + (endHeight - before.bottom + before.top) * fraction).roundToInt()
            // Children retain the target measurement. No text fitting or
            // comparison-layout breakpoint is evaluated on animation frames.
            view.layout(view.left, view.top, view.left + width, view.top + height)
            view.translationX = (before.left - after.left) * (1f - fraction)
            view.translationY = (before.top - after.top) * (1f - fraction)
            view.invalidate()
        }
        frame(0f)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = moveDuration
            interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    frame(1f)
                    view.setTag(R.id.dashboard_resize_animating, false)
                    sizes.remove(holder)
                    dispatchAnimationFinished(holder)
                    view.requestLayout()
                    if (!isRunning) dispatchAnimationsFinished()
                }
            })
        }
        sizes[holder] = animator
        animator.start()
        return true
    }
    override fun endAnimation(item: RecyclerView.ViewHolder) {
        sizes[item]?.cancel()
        super.endAnimation(item)
    }
    override fun endAnimations() {
        sizes.values.toList().forEach { it.cancel() }
        super.endAnimations()
    }
    override fun isRunning() = sizes.isNotEmpty() || super.isRunning()
}
