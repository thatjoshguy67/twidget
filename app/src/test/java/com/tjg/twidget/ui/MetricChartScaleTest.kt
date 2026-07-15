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
}
