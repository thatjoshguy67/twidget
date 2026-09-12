package com.tjg.twidget.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPostPolicyTest {
    @Test
    fun acceptsOwnOriginalTweetInWindow() {
        val status = FxStatusCandidate(
            type = "status",
            authorUsername = "person",
            url = "https://x.com/person/status/1",
            timestamp = 1_700_000_000_000L,
            id = "1",
            conversationId = "1",
            isRepost = false,
            isReply = false,
        )
        assertTrue(
            ActivityPostPolicy.isOwnOriginalInWindow(
                status,
                "person",
                windowStart = 1_699_000_000_000L,
                now = 1_700_000_000_000L,
            ),
        )
    }

    @Test
    fun rejectsOwnReplyInWindow() {
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
        assertFalse(
            ActivityPostPolicy.isOwnOriginalInWindow(
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
            ActivityPostPolicy.isOwnOriginalInWindow(
                status,
                "person",
                windowStart = 1_699_000_000_000L,
                now = 1_700_000_000_000L,
            ),
        )
    }
}
