package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopFollowersProgressTest {
    @Test
    fun percentageIsBoundedAndUnavailableWithoutKnownTotal() {
        assertEquals(0, TopFollowersProgress.percentage(0, 5_193L))
        assertEquals(12, TopFollowersProgress.percentage(645, 5_193L))
        assertEquals(100, TopFollowersProgress.percentage(6_000, 5_193L))
        assertEquals(0, TopFollowersProgress.percentage(-1, 5_193L))
        assertNull(TopFollowersProgress.percentage(100, null))
        assertNull(TopFollowersProgress.percentage(100, 0L))
    }
}
