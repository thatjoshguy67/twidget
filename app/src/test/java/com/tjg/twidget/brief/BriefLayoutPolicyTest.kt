package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefLayoutPolicyTest {
    @Test
    fun phoneWidthsUseOneColumn() {
        assertEquals(1, BriefLayoutPolicy.columnCount(599))
    }

    @Test
    fun largeScreensUseTwoColumns() {
        assertEquals(2, BriefLayoutPolicy.columnCount(600))
        assertEquals(2, BriefLayoutPolicy.columnCount(1280))
    }

    @Test
    fun masonryPlacementChoosesTheShortestColumn() {
        assertEquals(1, BriefLayoutPolicy.shortestColumn(intArrayOf(900, 480)))
        assertEquals(0, BriefLayoutPolicy.shortestColumn(intArrayOf(480, 900)))
        assertEquals(0, BriefLayoutPolicy.shortestColumn(intArrayOf(480, 480)))
    }
}
