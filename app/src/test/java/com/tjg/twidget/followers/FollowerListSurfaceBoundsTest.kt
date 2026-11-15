package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowerListSurfaceBoundsTest {
    @Test fun middleOfLongListHasNoRoundedViewportEnds() {
        assertEquals(FollowerListSurfaceBounds(-28, 828),
            followerListSurfaceBounds(800, 28, 100, null, null, 4))
    }
    @Test fun initialListRoundsOnlyItsActualBeginning() {
        assertEquals(FollowerListSurfaceBounds(0, 828),
            followerListSurfaceBounds(800, 28, 100, 0, null, 4))
    }
    @Test fun topCornerScrollsAwayWithFirstRow() {
        assertEquals(FollowerListSurfaceBounds(-12, 828),
            followerListSurfaceBounds(800, 28, 100, -12, null, 4))
    }
    @Test fun shortSearchResultsRetainTheirRoundedEnd() {
        assertEquals(FollowerListSurfaceBounds(0, 144),
            followerListSurfaceBounds(800, 28, 2, 0, 140, 4))
    }
    @Test fun lastRowEnteringViewportDoesNotPrematurelyClipBottom() {
        assertEquals(FollowerListSurfaceBounds(-28, 828),
            followerListSurfaceBounds(800, 28, 100, null, 850, 4))
    }
    @Test fun endOfListRoundsAtLastRowAboveFloatingControls() {
        assertEquals(FollowerListSurfaceBounds(-28, 704),
            followerListSurfaceBounds(800, 28, 100, null, 700, 4))
    }
    @Test fun emptyResultsHaveNoSurface() {
        assertEquals(FollowerListSurfaceBounds(0, 0),
            followerListSurfaceBounds(800, 28, 0, null, null, 4))
    }
}
