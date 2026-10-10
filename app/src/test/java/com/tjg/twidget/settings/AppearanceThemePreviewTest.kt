package com.tjg.twidget.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceThemePreviewTest {
    @Test fun largeSamsungFoldDoesNotBecomeTabletAtHigherResolution() {
        for (width in listOf(600f, 720f, 840f, 960f)) {
            assertEquals(AppearanceThemePreview.FOLD, AppearanceThemePreview.forDevice(
                width, isTabletDevice = false, hasHinge = false, isSamsungDevice = true,
            ))
        }
    }

    @Test fun hingeIdentifiesNonSamsungFold() {
        assertEquals(AppearanceThemePreview.FOLD, AppearanceThemePreview.forDevice(
            840f, isTabletDevice = false, hasHinge = true, isSamsungDevice = false,
        ))
    }

    @Test fun closedFoldAndLandscapePhoneKeepPhoneArtwork() {
        for (hasHinge in listOf(false, true)) {
            assertEquals(AppearanceThemePreview.PHONE, AppearanceThemePreview.forDevice(
                400f, isTabletDevice = false, hasHinge = hasHinge, isSamsungDevice = true,
            ))
        }
    }

    @Test fun tabletCategoryTakesPriorityOverScreenZoomAndWindowSize() {
        for (width in listOf(500f, 600f, 960f)) {
            assertEquals(AppearanceThemePreview.TABLET, AppearanceThemePreview.forDevice(
                width, isTabletDevice = true, hasHinge = false, isSamsungDevice = true,
            ))
        }
    }

    @Test fun genericLargeDisplayUsesTabletArtwork() {
        assertEquals(AppearanceThemePreview.TABLET, AppearanceThemePreview.forDevice(
            800f, isTabletDevice = false, hasHinge = false, isSamsungDevice = false,
        ))
    }
}
