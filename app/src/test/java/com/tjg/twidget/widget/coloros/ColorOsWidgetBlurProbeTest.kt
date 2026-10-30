package com.tjg.twidget.widget.coloros

import com.tjg.twidget.widget.WidgetStyle
import org.junit.Assert.*
import org.junit.Test

class ColorOsWidgetBlurProbeTest {
    @Test fun fallbackSizesMatchTheActualAllocations() {
        assertEquals(listOf(160 to 180, 320 to 60), ColorOsWidgetBlurProbe.fallbackSizes(160, 60, 320, 180))
        assertEquals(listOf(160 to 60), ColorOsWidgetBlurProbe.fallbackSizes(160, 60, 160, 60))
        assertEquals(listOf(1 to 1), ColorOsWidgetBlurProbe.fallbackSizes(0, -1, 0, -1))
    }

    @Test fun onlyTheDebugProbeWithColorOsStyleUsesTheHostSurface() {
        for (debug in listOf(false, true)) {
            for (provider in listOf(null, "com.tjg.twidget.TwidgetWidget", ColorOsWidgetBlurProbe.PROVIDER)) {
                for (style in WidgetStyle.entries) {
                    assertEquals(debug && provider == ColorOsWidgetBlurProbe.PROVIDER && style == WidgetStyle.COLOR_OS,
                        ColorOsWidgetBlurProbe.usesHostSurface(debug, provider, style))
                }
            }
        }
    }
}
