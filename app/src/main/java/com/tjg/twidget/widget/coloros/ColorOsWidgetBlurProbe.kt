package com.tjg.twidget.widget.coloros

import com.tjg.twidget.widget.WidgetStyle

/** Compatibility class-name check for the ColorOS Followers provider. */
internal object ColorOsWidgetBlurProbe {
    const val PROVIDER = "com.heytap.quicksearchbox.ui.appwidgets.DesktopSearchWidgetProvider"

    fun usesHostSurface(available: Boolean, provider: String?, style: WidgetStyle): Boolean =
        available && provider == PROVIDER && style == WidgetStyle.COLOR_OS
}
