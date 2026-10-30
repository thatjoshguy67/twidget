package com.tjg.twidget.widget.coloros

import com.tjg.twidget.widget.WidgetStyle
import org.junit.Assert.*
import org.junit.Test

class ColorOsWidgetBlurProbeTest {
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
