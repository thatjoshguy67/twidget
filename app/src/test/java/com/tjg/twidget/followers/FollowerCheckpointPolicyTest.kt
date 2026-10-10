package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Test

class FollowerCheckpointPolicyTest {
    @Test fun largerListsUseHundredFollowerCheckpoints() {
        assertEquals(listOf(0, 99, 199, 299), followerCheckpointPositions(2500).take(4))
    }
    @Test fun smallListsOfferUsefulCheckpointsAndNeverExceedTheirRows() {
        assertEquals(listOf(0, 4, 9, 14, 19, 24, 29), followerCheckpointPositions(30))
        assertEquals(listOf(0, 1, 2), followerCheckpointPositions(3))
        assertEquals(emptyList<Int>(), followerCheckpointPositions(0))
    }
}
