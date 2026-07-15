package com.tjg.twidget.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleAccountMappingTest {
    @Test
    fun resolvesTrackedHandlesCaseInsensitively() {
        val mappings = mapOf("owen" to "postpone_owen")

        assertEquals("postpone_owen", ScheduleAccountMapping.resolve(mappings, "  @OwEn "))
    }

    @Test
    fun blankUpdateClearsMapping() {
        val mappings = mapOf("owen" to "postpone_owen")
        val updated = ScheduleAccountMapping.updated(mappings, "@Owen", " ")

        assertNull(ScheduleAccountMapping.resolve(updated, "owen"))
    }

    @Test
    fun updateNormalizesBothSides() {
        val updated = ScheduleAccountMapping.updated(emptyMap(), " @OWEN ", " @MyPostpone ")

        assertEquals(mapOf("owen" to "mypostpone"), updated)
    }
}
