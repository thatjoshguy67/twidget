package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleAccountMappingTest {
    @Test
    fun resolvesTrackedHandlesCaseInsensitively() {
        val mappings = mapOf("owen" to "buffer-channel-id")

        assertEquals("buffer-channel-id", ScheduleAccountMapping.resolve(mappings, "  @OwEn "))
    }

    @Test
    fun blankUpdateClearsMapping() {
        val mappings = mapOf("owen" to "buffer-channel-id")
        val updated = ScheduleAccountMapping.updated(mappings, "@Owen", " ")

        assertNull(ScheduleAccountMapping.resolve(updated, "owen"))
    }

    @Test
    fun updateNormalizesTrackedHandleAndPreservesChannelId() {
        val updated = ScheduleAccountMapping.updated(emptyMap(), " @OWEN ", " Channel_ABC ")

        assertEquals(mapOf("owen" to "Channel_ABC"), updated)
    }
}
