package com.tjg.twidget.main

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Spatial hysteresis keeps animated neighbours from repeatedly changing the chosen slot. */
internal class DashboardDropTargetPolicy(private val movementSlop: Float) {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val area get() = ((right - left) * (bottom - top)).coerceAtLeast(0f)
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }
    data class Target(val id: String, val bounds: Bounds)
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastScroll = 0
    private var directionX = 0f
    private var directionY = 0f
    private var sticky: String? = null

    fun reset() { lastX = Float.NaN; lastY = Float.NaN; sticky = null; directionX = 0f; directionY = 0f }
    fun begin(left: Float, top: Float, scroll: Int) {
        reset(); lastX = left; lastY = top; lastScroll = scroll
    }
    fun choose(drag: Bounds, targets: List<Target>, scroll: Int): String? {
        val dx = drag.left - lastX
        val dy = drag.top - lastY
        val scrolled = abs(scroll - lastScroll) >= movementSlop
        if (!lastX.isNaN() && !scrolled && abs(dx) < movementSlop && abs(dy) < movementSlop) return null
        val reversed = !lastX.isNaN() && if (abs(directionX) >= abs(directionY)) dx * directionX < 0 else dy * directionY < 0
        if (reversed) sticky = null
        val scores = targets.mapNotNull { target -> score(drag, target.bounds).takeIf { it > 0f }?.let { target.id to it } }
        val best = scores.maxByOrNull { it.second } ?: return null
        val previous = scores.firstOrNull { it.first == sticky }
        val chosen = if (previous != null && best.second <= previous.second * 1.15f) previous.first else best.first
        if (chosen == sticky && !scrolled) return null
        sticky = chosen
        directionX = if (lastX.isNaN()) 0f else dx
        directionY = if (lastY.isNaN()) 0f else dy
        lastX = drag.left; lastY = drag.top; lastScroll = scroll
        return chosen
    }
    companion object {
        fun score(drag: Bounds, target: Bounds): Float {
            if (drag.area <= 0 || target.area <= 0) return 0f
            val overlap = (min(drag.right, target.right) - max(drag.left, target.left)).coerceAtLeast(0f) *
                (min(drag.bottom, target.bottom) - max(drag.top, target.top)).coerceAtLeast(0f)
            val covered = overlap / target.area
            val contained = overlap / drag.area
            val insideLarge = target.area >= drag.area * 1.75f && contained >= .65f &&
                drag.centerX in target.left..target.right && drag.centerY in target.top..target.bottom
            return if (covered >= .5f || contained >= .9f || insideLarge)
                max(covered, if (insideLarge || contained >= .9f) contained else 0f) else 0f
        }
    }
}
