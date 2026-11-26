package com.tjg.twidget.data

import org.junit.Assert.*
import org.junit.Test

class FollowerContextTest {
    private val now = 1_800_000_000_000L
    private val context = FollowerContext(now, now + 3_600_000, "event", now - 86_400_000, now + 86_400_000)
    private fun stats(n: Long) = ProfileStats("Example", "example", n, 10, 10, 10, syncedAt = now)
    private fun history(n: Long) = listOf(HistorySample("", n, 10, 10, 10, now - 86_400_000))
    @Test fun requiresLocalObservedLossAsWellAsSharedEvidence() {
        assertTrue(context.overlapsDecline(stats(80), history(100), now))
        assertFalse(context.overlapsDecline(stats(110), history(100), now))
        assertFalse(context.overlapsDecline(stats(80), history(100).map { it.copy(estimated = true) }, now))
        assertFalse(context.overlapsDecline(stats(80), history(100).map { it.copy(followersKnown = false) }, now))
    }
    @Test fun staleOrUnrelatedEventsCannotQualifyTodaysPerformance() {
        assertFalse(context.copy(expiresAt = now - 1).overlapsDecline(stats(80), history(100), now))
        assertFalse(context.copy(startAt = now + 86_400_000, endAt = now + 2 * 86_400_000).overlapsDecline(stats(80), history(100), now))
        assertFalse(context.copy(eventId = "").overlapsDecline(stats(80), history(100), now))
        assertFalse(context.copy(expiresAt = now + 10 * 86_400_000).overlapsDecline(stats(80), history(100), now))
    }
}
