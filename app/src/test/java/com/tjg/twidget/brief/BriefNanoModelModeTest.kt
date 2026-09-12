package com.tjg.twidget.brief

import org.junit.Assert.assertEquals
import org.junit.Test

class BriefNanoModelModeTest {
    @Test
    fun storageIdsRoundTrip() {
        BriefNanoModelMode.entries.forEach { mode ->
            assertEquals(mode, BriefNanoModelMode.fromStorageId(mode.storageId))
        }
    }

    @Test
    fun unknownStorageIdFallsBackToAutomaticStableSelection() {
        assertEquals(BriefNanoModelMode.AUTO_STABLE, BriefNanoModelMode.fromStorageId(null))
        assertEquals(BriefNanoModelMode.AUTO_STABLE, BriefNanoModelMode.fromStorageId("future_mode"))
    }

    @Test
    fun automaticStableSelectionTriesFastThenFull() {
        assertEquals(
            listOf(BriefNanoModelMode.STABLE_FAST, BriefNanoModelMode.STABLE_FULL),
            BriefNanoModelMode.AUTO_STABLE.probeOrder(),
        )
    }
}
