package com.tjg.twidget.main

import android.graphics.Rect
import android.view.View
import android.widget.GridLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.oneui.responsivepane.ResponsivePaneLayout
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveDashboardLaunchInstrumentedTest {
    @Test fun nativeDrawerPreservesCardGeometry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val expanded = java.util.concurrent.CountDownLatch(1)
            var width = 0
            var cardWidth = 0
            scenario.onActivity { activity ->
                assertFalse("Dashboard header starts collapsed", activity.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.main_toolbar_layout).isExpanded)
                val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
                assertNotNull(grid)
                assertEquals(if (activity.resources.configuration.screenWidthDp >= 600) 4 else 2, grid.columnCount)
                assertTrue(grid.childCount > 0)
                val menu = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.drawer_nav)
                assertEquals("Accounts, native group divider and add-account item",
                    com.tjg.twidget.data.TwidgetStore.accounts(activity).size + 2, menu.adapter!!.itemCount)
                width = grid.width
                cardWidth = grid.getChildAt(0).width
                val height = grid.getChildAt(0).height
                for (i in 0 until grid.childCount) assertEquals(height, grid.getChildAt(i).height)
                if (activity.resources.configuration.screenWidthDp >= 600) {
                    assertTrue(activity.findViewById<View>(dev.oneuiproject.oneui.design.R.id.navRailDrawerButton)
                        .getGlobalVisibleRect(Rect()))
                }
                val drawer = activity.findViewById<View>(com.google.android.material.R.id.responsive_pane_drawer)
                val pane = activity.findViewById<View>(R.id.dashboard_root) as? ResponsivePaneLayout
                com.tjg.twidget.ui.SeslSurfaceHighlight.applyTo(drawer)
                assertNotNull("Native drawer surface receives the SESL 9.5 rim", drawer.foreground)
                assertEquals("Collapsed rail remains transparent", if (pane != null) 0 else 255, drawer.foreground.alpha)
                val expected = pane?.getPaneConfig(com.google.android.material.oneui.responsivepane.model.PaneState.OPENED)?.width ?: drawer.width
                drawer.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                    if (right - left == expected) expanded.countDown()
                }
                activity.drawerController.setDrawerOpen(true, false)
                if (drawer.width == expected) expanded.countDown()
            }
            assertTrue("Native expanded drawer must finish layout", expanded.await(5, java.util.concurrent.TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
                assertTrue(activity.drawerController.isDrawerOpen)
                val drawer = activity.findViewById<View>(com.google.android.material.R.id.responsive_pane_drawer)
                assertEquals("Expanded drawer shows the complete rim", 255, drawer.foreground.alpha)
                val root = activity.findViewById<View>(R.id.dashboard_root)
                val safe = androidx.core.view.ViewCompat.getRootWindowInsets(root)!!.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
                val gap = activity.resources.getDimensionPixelSize(R.dimen.dashboard_drawer_surface_gap)
                val position = IntArray(2).also(drawer::getLocationOnScreen)
                val origin = IntArray(2).also(root::getLocationOnScreen)
                assertTrue("Floating drawer leaves a start-edge gap", if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                    position[0] + drawer.width <= origin[0] + root.width - safe.right - gap
                    else position[0] >= origin[0] + safe.left + gap)
                assertTrue("Floating drawer clears the status bar with a gap", position[1] >= origin[1] + safe.top + gap)
                assertTrue("Floating drawer clears navigation with a gap",
                    position[1] + drawer.height <= origin[1] + root.height - safe.bottom - gap)
                assertEquals(width, grid.width)
                assertEquals(cardWidth, grid.getChildAt(0).width)
                assertTrue(activity.findViewById<View>(dev.oneuiproject.oneui.design.R.id.oui_des_drawer_header_button)
                    .getGlobalVisibleRect(Rect()))
                activity.drawerController.setDrawerOpen(false, false)
            }
        }
    }
}
