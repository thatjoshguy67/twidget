package com.tjg.twidget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetFontSettingsTest {
    @Test
    fun preservesEverySupportedWidgetFont() {
        assertEquals(TwidgetStore.FONT_SYSTEM, TwidgetStore.normalizeWidgetFont(TwidgetStore.FONT_SYSTEM))
        assertEquals(
            TwidgetStore.FONT_ONE_UI_SANS,
            TwidgetStore.normalizeWidgetFont(TwidgetStore.FONT_ONE_UI_SANS),
        )
        assertEquals(
            TwidgetStore.FONT_GOOGLE_SANS_FLEX,
            TwidgetStore.normalizeWidgetFont(TwidgetStore.FONT_GOOGLE_SANS_FLEX),
        )
    }

    @Test
    fun fallsBackToOneUiSansForUnknownOrMissingFonts() {
        assertEquals(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.normalizeWidgetFont(null))
        assertEquals(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.normalizeWidgetFont("unsupported"))
    }
}
