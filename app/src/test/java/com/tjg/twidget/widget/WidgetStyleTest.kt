package com.tjg.twidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStyleTest {
    @org.junit.Test fun platformDefaultsPreserveOneUiAndPixelAndRespectExplicitStyles() {
        org.junit.Assert.assertEquals(WidgetStyle.ONE_UI, WidgetStyle.resolve(null, oneUi = true, colorOs = false))
        org.junit.Assert.assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve(null, oneUi = false, colorOs = false))
        org.junit.Assert.assertEquals(WidgetStyle.COLOR_OS, WidgetStyle.resolve(null, oneUi = false, colorOs = true))
        for (oneUi in listOf(false, true)) for (colorOs in listOf(false, true))
            for (style in WidgetStyle.supportedStyles(colorOs))
                org.junit.Assert.assertEquals(style, WidgetStyle.resolve(style.storedValue, oneUi, colorOs))
    }

    @Test fun defaultsFollowTheRomButAnExplicitChoiceWins() {
        assertEquals(WidgetStyle.ONE_UI, WidgetStyle.resolve(null, true))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve(null, false))
        assertEquals(WidgetStyle.ONE_UI, WidgetStyle.resolve("one_ui", false))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve("material", true))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve("unknown", false))
        assertEquals(WidgetStyle.COLOR_OS, WidgetStyle.resolve("color_os", true, colorOs = true))
        assertEquals(WidgetStyle.COLOR_OS, WidgetStyle.resolve("color_os", false, colorOs = true))
        assertEquals(com.tjg.twidget.data.TwidgetStore.FONT_SYSTEM, WidgetStyle.COLOR_OS.defaultFont)
    }

    @Test fun unsupportedColorOsProfilesAreHiddenAndSavedChoicesFallBackToTheHostStyle() {
        assertEquals(listOf(WidgetStyle.ONE_UI, WidgetStyle.MATERIAL), WidgetStyle.supportedStyles(colorOs = false))
        assertEquals(WidgetStyle.entries.toList(), WidgetStyle.supportedStyles(colorOs = true))
        // A backup restored from ColorOS must not select a hidden pager page.
        assertEquals(WidgetStyle.ONE_UI, WidgetStyle.resolve("color_os", oneUi = true, colorOs = false))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve("color_os", oneUi = false, colorOs = false))
    }

    @Test fun bodyEmphasisPreservesRanksAndLocalizedQuantities() {
        val text = "@JohnCena ranks as your #2 top follower with 100K views and 8.000 followers"
        assertEquals(listOf("@JohnCena", "100K", "8.000"),
            BriefWidgetArtworkRenderer.emphasisRanges(text).map { text.substring(it) })
    }
}
