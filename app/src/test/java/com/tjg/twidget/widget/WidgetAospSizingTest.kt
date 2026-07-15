package com.tjg.twidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAospSizingTest {
    @Test
    fun `pixel launcher 2x2 uses centered count artwork`() {
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_2X1,
            TwidgetWidget.layoutModeForAosp(width = 179, height = 99),
        )
    }

    @Test
    fun `pixel launcher one-row sizes stay compact`() {
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_2X1,
            TwidgetWidget.layoutModeForAosp(width = 179, height = 48),
        )
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP,
            TwidgetWidget.layoutModeForAosp(width = 360, height = 48),
        )
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP,
            TwidgetWidget.layoutModeForAosp(width = 360, height = 99),
        )
    }

    @Test
    fun `larger pixel launcher allocation uses large artwork`() {
        assertEquals(
            TwidgetWidget.LAYOUT_MODE_LARGE,
            TwidgetWidget.layoutModeForAosp(width = 373, height = 210),
        )
    }

    @Test
    fun `responsive variants cover each width and height bucket`() {
        assertEquals(
            listOf(
                Triple(110, 40, TwidgetWidget.LAYOUT_MODE_COMPACT_2X1),
                Triple(231, 40, TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP),
                Triple(110, 111, TwidgetWidget.LAYOUT_MODE_LARGE),
                Triple(231, 111, TwidgetWidget.LAYOUT_MODE_LARGE),
            ),
            TwidgetWidget.responsiveSpecs().map { Triple(it.minWidth, it.minHeight, it.mode) },
        )
    }

    @Test
    fun `responsive variant for current allocation uses exact artwork size`() {
        val current = TwidgetWidget.responsiveSpecs(currentWidth = 320, currentHeight = 280)
            .single { it.minWidth == 231 && it.minHeight == 111 }

        assertEquals(320, current.renderWidth)
        assertEquals(280, current.renderHeight)
    }
}
