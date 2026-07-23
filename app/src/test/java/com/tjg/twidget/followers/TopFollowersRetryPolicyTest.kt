package com.tjg.twidget.followers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopFollowersRetryPolicyTest {
    @Test
    fun transientFailuresUseBoundedBackoffBeforeWorkManagerRetry() {
        assertEquals(2_000L, TopFollowersRetryPolicy.delayMs(1))
        assertEquals(4_000L, TopFollowersRetryPolicy.delayMs(2))
        assertEquals(8_000L, TopFollowersRetryPolicy.delayMs(3))
        assertEquals(16_000L, TopFollowersRetryPolicy.delayMs(4))
        assertEquals(30_000L, TopFollowersRetryPolicy.delayMs(5))
        assertNull(TopFollowersRetryPolicy.delayMs(6))
        assertNull(TopFollowersRetryPolicy.delayMs(0))
    }
}
