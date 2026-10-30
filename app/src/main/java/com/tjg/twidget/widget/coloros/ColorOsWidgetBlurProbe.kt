package com.tjg.twidget.widget.coloros

import com.tjg.twidget.widget.WidgetStyle

/** Experimental launcher class-name check. Never changes ordinary widget component identities. */
internal object ColorOsWidgetBlurProbe {
    const val PROVIDER = "com.heytap.quicksearchbox.ui.appwidgets.DesktopSearchWidgetProvider"

    fun usesHostSurface(debug: Boolean, provider: String?, style: WidgetStyle): Boolean =
        debug && provider == PROVIDER && style == WidgetStyle.COLOR_OS
    /** Portrait and landscape allocations, never synthetic sizes larger than the host reports. */
    fun fallbackSizes(minWidth: Int, minHeight: Int, maxWidth: Int, maxHeight: Int): List<Pair<Int, Int>> =
        listOf(minWidth.coerceAtLeast(1) to maxHeight.coerceAtLeast(1),
            maxWidth.coerceAtLeast(1) to minHeight.coerceAtLeast(1)).distinct()
}
