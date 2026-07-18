package com.tjg.twidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricChartScaleTest {
    @Test
    fun screenshotRangeUsesRealMaximumAsTopGridline() {
        val bounds = MetricChartScale.axisBounds(listOf(229, 232, 232, 232, 231, 231))

        assertEquals(226L to 232L, bounds)
        assertEquals(listOf("232", "230", "228", "226"), MetricChartScale.axisLabels(bounds.first, bounds.second))
    }

    @Test
    fun zeroBasedRangeDoesNotAddUnusedHeadroom() {
        assertEquals(0L to 231L, MetricChartScale.axisBounds(listOf(231, 231, 231)))
        assertEquals(0L to 73L, MetricChartScale.axisBounds(listOf(0, 35, 73)))
    }

    @Test
    fun linePointsUseTheirExactPositionWithinTheAxis() {
        assertEquals(0f, MetricChartScale.yFraction(232, 226, 232))
        assertEquals(0.5f, MetricChartScale.yFraction(229, 226, 232))
        assertEquals(1f, MetricChartScale.yFraction(226, 226, 232))
    }

    @Test
    fun accountAverageDoesNotFlattenCurrentBars() {
        val bounds = MetricChartScale.primaryAxisBounds(
            primary = listOf(7_651L, 7_655L, 7_652L, 7_654L, 7_656L, 7_658L, 7_665L),
            background = listOf(6_650L, 6_660L, 6_665L, 6_670L, 6_675L, 6_680L, 6_685L),
        )

        assertEquals(7_635L to 7_665L, bounds)
        assertEquals(0.466f, MetricChartScale.yFraction(7_651L, bounds.first, bounds.second), 0.001f)
        assertEquals(0f, MetricChartScale.yFraction(7_665L, bounds.first, bounds.second))
    }

    @Test
    fun distinctDailyTotalsKeepDistinctPositions() {
        val bounds = MetricChartScale.axisBounds(listOf(7_649L, 4_651L))

        assertEquals(0L to 7_649L, bounds)
        assertEquals(0f, MetricChartScale.yFraction(7_649L, bounds.first, bounds.second))
        assertEquals(0.392f, MetricChartScale.yFraction(4_651L, bounds.first, bounds.second), 0.001f)
    }
}
