package com.tjg.twidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutPolicyTest {
    @Test fun `phone stays compact in both orientations`() {
        assertEquals(2, DashboardLayoutPolicy.columns(411, 411))
        assertEquals(2, DashboardLayoutPolicy.columns(411, 891))
        assertEquals(2, DashboardLayoutPolicy.columns(599, 1200))
    }

    @Test fun `unfolded and tablet windows use available logical width`() {
        assertEquals(4, DashboardLayoutPolicy.columns(600, 600))
        assertEquals(4, DashboardLayoutPolicy.columns(720, 680))
        assertEquals(4, DashboardLayoutPolicy.columns(800, 1100))
        assertEquals(2, DashboardLayoutPolicy.columns(720, 599))
        assertEquals(2, DashboardLayoutPolicy.columns(480, 800))
    }
}
