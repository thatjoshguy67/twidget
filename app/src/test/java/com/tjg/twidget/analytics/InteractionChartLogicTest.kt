package com.tjg.twidget.analytics

import com.tjg.twidget.main.donutSegments
import com.tjg.twidget.main.originalFraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionChartLogicTest {
    @Test fun donutUsesProportionalSegmentsWithGaps() {
        val segments = donutSegments(listOf(1, 3, 0, 0), 4f)
        assertEquals(2, segments.size)
        assertEquals(-88f, segments[0].start, 0.001f)
        assertEquals(86f, segments[0].sweep, 0.001f)
        assertEquals(266f, segments[1].sweep, 0.001f)
        assertEquals(360f, donutSegments(listOf(0, 4)).single().sweep, 0.001f)
        assertTrue(donutSegments(listOf(0, 0)).isEmpty())
    }
    @Test fun splitHandlesEmptyAndAsymmetricCounts() {
        assertNull(originalFraction(0, 0))
        assertNull(originalFraction(-1, 3))
        assertEquals(0.25f, originalFraction(1, 3)!!, 0.001f)
    }
}
