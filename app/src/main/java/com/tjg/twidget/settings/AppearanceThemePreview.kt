package com.tjg.twidget.settings

import com.tjg.twidget.R

internal enum class AppearanceThemePreview(val preferenceResource: Int, val widthDp: Int) {
    PHONE(R.xml.settings_appearance_theme, 73),
    FOLD(R.xml.settings_appearance_theme_wide, 140),
    TABLET(R.xml.settings_appearance_theme_tablet, 160);

    companion object {
        fun forDevice(
            smallestDisplayWidthDp: Float,
            isTabletDevice: Boolean,
            hasHinge: Boolean,
            isSamsungDevice: Boolean,
        ): AppearanceThemePreview = when {
            isTabletDevice -> TABLET
            smallestDisplayWidthDp < 600 -> PHONE
            // Samsung exposes its tablet category separately. A large inner display
            // on a Samsung phone remains a Fold even above the old 720dp cutoff.
            hasHinge || isSamsungDevice -> FOLD
            else -> TABLET
        }
    }
}
