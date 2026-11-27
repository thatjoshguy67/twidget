package com.tjg.twidget.main

import org.junit.Assert.*
import org.junit.Test

class DashboardEdgeScrollRampTest {
    @Test fun acceleratesWithDwellAndCapsSpeed() {
        val ramp = DashboardEdgeScrollRamp()
        val first = ramp.speed(1f, 100)
        val middle = ramp.speed(1f, 1000)
        val full = ramp.speed(1f, 1900)
        assertTrue(first < middle && middle < full)
        assertEquals(900f, full, .01f)
        assertEquals(full, ramp.speed(1f, 9000), .01f)
    }
    @Test fun leavingOrChangingEdgesRestartsTheRampAndDepthStillControlsSpeed() {
        val ramp = DashboardEdgeScrollRamp()
        ramp.speed(1f, 0)
        assertEquals(450f, ramp.speed(.5f, 1800), .01f)
        assertEquals(-90f, ramp.speed(-1f, 1900), .01f)
        assertEquals(0f, ramp.speed(0f, 2000), .01f)
        assertEquals(90f, ramp.speed(1f, 3000), .01f)
    }
}
