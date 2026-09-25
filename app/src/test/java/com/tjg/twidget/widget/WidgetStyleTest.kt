package com.tjg.twidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStyleTest {
    @Test fun defaultsFollowTheRomButAnExplicitChoiceWins() {
        assertEquals(WidgetStyle.ONE_UI, WidgetStyle.resolve(null, true))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve(null, false))
        assertEquals(WidgetStyle.ONE_UI, WidgetStyle.resolve("one_ui", false))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve("material", true))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.resolve("unknown", false))
    }

    @Test fun bodyEmphasisPreservesRanksAndLocalizedQuantities() {
        val text = "@JohnCena ranks as your #2 top follower with 100K views and 8.000 followers"
        assertEquals(listOf("@JohnCena", "100K", "8.000"),
            BriefWidgetArtworkRenderer.emphasisRanges(text).map { text.substring(it) })
    }
}
