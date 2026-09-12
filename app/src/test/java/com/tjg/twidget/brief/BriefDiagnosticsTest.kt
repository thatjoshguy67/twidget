package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefDiagnosticsTest {
    private val base = BriefSnapshot(
        username = "example",
        generatedAt = 1L,
        sourceSyncedAt = 1L,
        analyticsCachedAt = 0L,
        followerScanCompletedAt = 0L,
        followers = 8_000,
        following = 100,
        posts = 500,
        followersToday = 1,
        followersWeek = 7,
        cards = listOf(BriefCard("real", BriefCardType.SUMMARY, "Real", "Real body", 50)),
        topFollowerRanks = emptyMap(),
    )

    @Test
    fun `real scenario leaves engine output untouched`() {
        assertEquals(base, BriefDebugScenario.REAL.snapshot(base))
    }

    @Test
    fun `synthetic scenarios create one template card without an AI request`() {
        BriefDebugScenario.entries.filterNot { it == BriefDebugScenario.REAL }.forEach { scenario ->
            val snapshot = scenario.snapshot(base)
            assertEquals(1, snapshot.cards.size)
            assertEquals(BriefProviderUsed.TEMPLATE, snapshot.providerUsed)
            assertTrue(snapshot.providerMessage.contains("Synthetic debug state"))
            assertNotEquals("real", snapshot.cards.single().id)
        }
    }
}
