package com.tjg.twidget.social

import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import org.junit.Assert.*
import org.junit.Test

class LegacySocialMigrationTest {
    @Test fun casingAndAtSignProduceTheSameDeterministicId() {
        assertEquals(LegacySocialMigration.accountId(" @Name "), LegacySocialMigration.accountId("name"))
        assertNotEquals(LegacySocialMigration.accountId("name"), LegacySocialMigration.accountId("another"))
    }

    @Test fun deduplicatesLegacyAccountsAndPreservesDefaultAndPinnedAccounts() {
        val snapshot = LegacySocialSnapshot(listOf("Name", "@NAME", "Other").map {
            LegacySocialAccount(it, null, emptyList())
        }, "other", mapOf(1 to "@Name", 2 to ""), true)
        val result = LegacySocialMigration.catalog(snapshot)
        assertEquals(2, result.accounts.size)
        assertEquals(LegacySocialMigration.profileId("Other"), result.defaultProfileId)
        assertEquals(LegacySocialMigration.accountId("name"), result.widgets[0].accountId)
        assertEquals(LegacySocialMigration.accountId("other"), result.widgets[1].accountId)
        assertTrue(result.widgets[1].followsDefault)
        assertEquals(result, LegacySocialMigration.catalog(snapshot))
    }

    @Test fun unknownCountersAndImportedEstimatedFlagsSurviveConversion() {
        val sample = HistorySample("Sep 18", 123, 0, 0, 0, 100,
            estimated = true, followingKnown = false, postsKnown = false, likesKnown = false,
            imported = true, sharedImport = true)
        val rows = LegacySocialMigration.observations(LegacySocialAccount("name", null, listOf(sample)))
        assertEquals(4, rows.size)
        assertEquals(123L, rows.first { it.metric == SocialMetric.FOLLOWERS }.value)
        assertNull(rows.first { it.metric == SocialMetric.FOLLOWING }.value)
        assertTrue(rows.all { it.estimated && it.imported && it.sharedImport })
    }

    @Test fun currentRealValuesWinOverEstimateAtTheSameTimestamp() {
        val stats = ProfileStats("Name", "name", 0, 0, 0, 0, syncedAt = 100)
        val estimate = HistorySample("Sep 18", 123, 10, 3, 2, 100, estimated = true)
        val rows = LegacySocialMigration.observations(LegacySocialAccount("name", stats, listOf(estimate)))
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.value == 0L && !it.estimated })
    }

    @Test fun unsyncedAccountHasNoInventedObservation() {
        val stats = ProfileStats("Name", "name", 0, 0, 0, 0, syncedAt = 0)
        assertTrue(LegacySocialMigration.observations(LegacySocialAccount("name", stats, emptyList())).isEmpty())
        assertEquals(SocialCatalog(), LegacySocialMigration.catalog(LegacySocialSnapshot(emptyList(), "", emptyMap(), false)))
    }
}
