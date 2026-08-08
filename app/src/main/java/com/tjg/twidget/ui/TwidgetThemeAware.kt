package com.tjg.twidget.ui

/** Views that hold cached theme colors and must refresh when accent or mode changes. */
fun interface TwidgetThemeAware {
    fun applyTwidgetTheme()
}
