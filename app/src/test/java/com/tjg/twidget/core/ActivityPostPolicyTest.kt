package com.tjg.twidget.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPostPolicyTest {
    @Test
    fun acceptsOwnReplyInWindow() {
        val status = FxStatusCandidate(
            type = "status",
            authorUsername = "person",
            url = "https://x.com/person/status/1",
            timestamp = 1_700_000_000_000L,
            id = "1",
            conversationId = "999",
            isRepost = false,
            isReply = true,
        )
        assertTrue(
            ActivityPostPolicy.isOwnPostOrReplyInWindow(
                status,
                "person",
                windowStart = 1_699_000_000_000L,
                now = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun rejectsReposts() {
        val status = FxStatusCandidate(
            type = "status",
            authorUsername = "person",
            url = "https://x.com/person/status/1",
            timestamp = 1_700_000_000_000L,
            id = "1",
            conversationId = "1",
            isRepost = true,
            isReply = false,
        )
        assertFalse(
            ActivityPostPolicy.isOwnPostOrReplyInWindow(
                status,
                "person",
                windowStart = 1_699_000_000_000L,
                now = 1_700_000_000_000L,
            ),
        )
    }
}
