package com.tjg.twidget.main

import kotlin.math.sign

/** Speed in dp/s, restarting the dwell time whenever the finger leaves or changes edge. */
internal class DashboardEdgeScrollRamp {
    private var direction = 0f
    private var enteredAt = 0L

    fun reset() { direction = 0f }

    fun speed(proximity: Float, now: Long): Float {
        if (proximity == 0f) { reset(); return 0f }
        if (sign(proximity) != direction) {
            direction = sign(proximity)
            enteredAt = now
        }
        val progress = ((now - enteredAt) / 1800f).coerceIn(0f, 1f)
        return proximity.coerceIn(-1f, 1f) * (90f + 810f * progress * progress)
    }
}
