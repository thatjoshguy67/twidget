package com.tjg.twidget.widget.coloros

import com.tjg.twidget.widget.WidgetStyle

/** Experimental launcher class-name check. Never changes ordinary widget component identities. */
internal object ColorOsWidgetBlurProbe {
    const val PROVIDER = "com.heytap.quicksearchbox.ui.appwidgets.DesktopSearchWidgetProvider"

    fun usesHostSurface(debug: Boolean, provider: String?, style: WidgetStyle): Boolean =
        debug && provider == PROVIDER && style == WidgetStyle.COLOR_OS
}
