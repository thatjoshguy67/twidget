package com.tjg.twidget.ui

/** Logical window size, rather than pixels or orientation, controls dashboard density. */
internal object DashboardLayoutPolicy {
    const val MAX_CONTENT_WIDTH_DP = 1200
    fun columns(smallestWidthDp: Int, availableWidthDp: Int): Int =
        if (smallestWidthDp >= 600 && availableWidthDp >= 600) 4 else 2

    fun compactBrief(windowWidthDp: Int, windowHeightDp: Int): Boolean =
        windowWidthDp > windowHeightDp && windowHeightDp in 1..599

    fun briefHeightDp(windowWidthDp: Int, windowHeightDp: Int, fontScale: Float): Int =
        (when {
            compactBrief(windowWidthDp, windowHeightDp) -> 120
            windowWidthDp <= 340 -> 184
            else -> 160
        } + 80 * (fontScale - 1f).coerceAtLeast(0f)).toInt()
}
