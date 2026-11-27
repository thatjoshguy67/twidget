package com.tjg.twidget.ui

/** Logical window size, rather than pixels or orientation, controls dashboard density. */
internal object DashboardLayoutPolicy {
    fun columns(smallestWidthDp: Int, availableWidthDp: Int): Int =
        if (smallestWidthDp >= 600 && availableWidthDp >= 600) 4 else 2
}
