package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefDiagnosticsTest {
    private val strings = TestBriefStrings()
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
        assertEquals(base, BriefDebugScenario.REAL.snapshot(strings, base))
    }

    @Test
    fun `synthetic scenarios create one template card without an AI request`() {
        BriefDebugScenario.entries.filterNot { it == BriefDebugScenario.REAL || it == BriefDebugScenario.COMPONENTS || it == BriefDebugScenario.INSIGHTS || it == BriefDebugScenario.CHATGPT_LINK }.forEach { scenario ->
            val snapshot = scenario.snapshot(strings, base)
            assertEquals(1, snapshot.cards.size)
            assertEquals(BriefProviderUsed.TEMPLATE, snapshot.providerUsed)
            assertTrue(snapshot.providerMessage.contains("Synthetic debug state"))
            assertNotEquals("real", snapshot.cards.single().id)
        }
    }

    @Test
    fun `component library combines the synthetic cards without using the real card`() {
        val library = BriefDebugScenario.COMPONENTS.snapshot(strings, base)
        val scenarios = BriefDebugScenario.entries.filterNot { it == BriefDebugScenario.REAL || it == BriefDebugScenario.COMPONENTS }
        assertEquals(scenarios.flatMap { it.snapshot(strings, base).cards }, library.cards)
        assertEquals(BriefProviderUsed.TEMPLATE, library.providerUsed)
        assertTrue(library.cards.none { it.id == "real" })
        assertEquals(base.cards, listOf(BriefCard("real", BriefCardType.SUMMARY, "Real", "Real body", 50)))
    }
}
