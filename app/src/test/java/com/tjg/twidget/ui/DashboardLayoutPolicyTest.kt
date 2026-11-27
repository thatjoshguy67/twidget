package com.tjg.twidget.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardLayoutPolicyTest {
    @Test fun `brief adapts to narrow width short height and larger type`() {
        assertEquals(184, DashboardLayoutPolicy.briefHeightDp(320, 568, 1f))
        assertEquals(160, DashboardLayoutPolicy.briefHeightDp(411, 891, 1f))
        assertEquals(120, DashboardLayoutPolicy.briefHeightDp(891, 411, 1f))
        assertEquals(160, DashboardLayoutPolicy.briefHeightDp(1280, 800, 1f))
        assertEquals(200, DashboardLayoutPolicy.briefHeightDp(320, 568, 1.2f))
    }

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
