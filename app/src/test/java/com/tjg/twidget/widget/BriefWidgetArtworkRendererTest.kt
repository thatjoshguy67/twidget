package com.tjg.twidget.widget

import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefCardType
import dev.oneuiproject.oneui.R as OneUiIconR
import org.junit.Assert.assertEquals
import org.junit.Test

class BriefWidgetArtworkRendererTest {
    @Test
    fun mapsEachFigmaWidgetSizeToItsDedicatedComposition() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.COMPACT_STRIP,
            BriefWidgetArtworkRenderer.layout(162f, 76f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_STRIP,
            BriefWidgetArtworkRenderer.layout(352f, 76f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.SQUARE,
            BriefWidgetArtworkRenderer.layout(162f, 176f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_TALL,
            BriefWidgetArtworkRenderer.layout(352f, 175f),
        )
    }

    @Test
    fun layoutSelectionToleratesLauncherSizeVariation() {
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.COMPACT_STRIP,
            BriefWidgetArtworkRenderer.layout(170f, 80f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_TALL,
            BriefWidgetArtworkRenderer.layout(340f, 168f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.MEDIUM_TALL,
            BriefWidgetArtworkRenderer.layout(270f, 176f),
        )
        assertEquals(
            BriefWidgetArtworkRenderer.Layout.WIDE_TALL,
            BriefWidgetArtworkRenderer.layout(300f, 149f),
        )
    }

    @Test
    fun supportingArtworkFollowsTheDynamicCardType() {
        assertEquals(
            R.drawable.ic_import_analytics,
            BriefWidgetArtworkRenderer.supportingIcon(BriefCardType.GROWTH),
        )
        assertEquals(
            R.drawable.ic_milestone_goals,
            BriefWidgetArtworkRenderer.supportingIcon(BriefCardType.MILESTONE),
        )
        assertEquals(
            OneUiIconR.drawable.ic_oui_community,
            BriefWidgetArtworkRenderer.supportingIcon(BriefCardType.TOP_FOLLOWER),
        )
    }

    @Test
    fun tallCardUsesNowBriefProportions() {
        val metrics = BriefWidgetArtworkRenderer.tallCardMetrics(352f, 175f)

        assertEquals(14f, metrics.iconInsetDp)
        assertEquals(16f, metrics.textInsetDp)
        assertEquals(16f, metrics.bottomInsetDp)
        assertEquals(42.875f, metrics.iconSizeDp)
        assertEquals(20f, metrics.titleSizeSp)
        assertEquals(14f, metrics.bodySizeSp)
        assertEquals(6f, metrics.textGapDp)
        assertEquals(600, metrics.titleWeight)
    }

    @Test
    fun compactTallCardsUseSmallerTypeToPreserveTwoDescriptionLines() {
        val square = BriefWidgetArtworkRenderer.tallCardMetrics(162f, 176f)
        val threeColumn = BriefWidgetArtworkRenderer.tallCardMetrics(270f, 176f)

        assertEquals(18f, square.titleSizeSp)
        assertEquals(12f, square.bodySizeSp)
        assertEquals(20f, threeColumn.titleSizeSp)
        assertEquals(12f, threeColumn.bodySizeSp)
    }

    @Test
    fun stripTextUsesTheRealFontBoundsForVerticalCentering() {
        assertEquals(
            46f,
            BriefWidgetArtworkRenderer.centeredFirstBaseline(
                height = 76f,
                fontTop = -20f,
                fontBottom = 4f,
                lineHeight = 24f,
                lineCount = 1,
            ),
        )
        assertEquals(
            34f,
            BriefWidgetArtworkRenderer.centeredFirstBaseline(
                height = 76f,
                fontTop = -20f,
                fontBottom = 4f,
                lineHeight = 24f,
                lineCount = 2,
            ),
        )
    }

    @Test
    fun oneUiFollowerEmphasisUsesBoldRatherThanExtraBold() {
        assertEquals(700, WidgetArtworkRenderer.ONE_UI_EMPHASIS_WEIGHT)
    }
}
