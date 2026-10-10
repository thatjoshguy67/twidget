package com.tjg.twidget.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetBitmapSizeTest {
    @Test fun oversizedSamsungArtworkFitsBothThemes() {
        // 360 x 640dp at 3x density exceeds the Android 17 limit on a 1080 x 2400 screen.
        val platformLimit = 1080L * 2400 * 4 * 3 / 2
        for (cap in listOf(12_000_000L, 8_000_000L)) {
            val totalBudget = minOf(cap, platformLimit * 3 / 4)
            val size = widgetBitmapSize(1080, 1920, totalBudget / 2)
            assertTrue(size.width.toLong() * size.height * 4 * 2 <= totalBudget)
            assertTrue(size.width < 1080 && size.height < 1920)
            assertEquals(1080.0 / 1920, size.width.toDouble() / size.height, 0.002)
        }
    }

    @Test fun ordinaryArtworkRetainsItsResolution() {
        assertEquals(WidgetBitmapSize(1056, 528), widgetBitmapSize(1056, 528, 4_000_000))
    }

    @Test fun oneThemeCanUseTheWholeBudget() {
        val single = widgetBitmapSize(1080, 1920, 8_000_000)
        val dual = widgetBitmapSize(1080, 1920, 8_000_000 / 2)
        assertTrue(single.width > dual.width && single.height > dual.height)
        assertTrue(single.width.toLong() * single.height * 4 <= 8_000_000)
    }

    @Test fun narrowAndHugeDimensionsCannotExceedTheBudget() {
        for ((width, height) in listOf(1 to 1_000_000, 1_000_000 to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val size = widgetBitmapSize(width, height, 1024)
            assertTrue(size.width > 0 && size.height > 0)
            assertTrue(size.width.toLong() * size.height * 4 <= 1024)
        }
    }
}
