package com.tjg.twidget.main

import android.content.res.Configuration
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.ui.DashboardRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardWindowSizeInstrumentedTest {
    @Test fun phoneRotationKeepsModalDrawerAtDifferentDensities() {
        checkWindow(411, 411, 891, 2, false)
        checkWindow(411, 891, 411, 2, false)
    }

    @Test fun unfoldedWindowUsesRailButGridAdaptsToItsUsableWidth() {
        checkWindow(720, 720, 900, 4, true)
        checkWindow(720, 900, 720, 4, true)
        checkWindow(500, 500, 900, 2, false)
    }

    private fun checkWindow(smallest: Int, width: Int, height: Int, columns: Int, rail: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (density in listOf(160, 480)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    smallestScreenWidthDp = smallest
                    screenWidthDp = width
                    screenHeightDp = height
                    densityDpi = density
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val layout = TypedValue().also { context.resources.getValue(R.layout.activity_main, it, true) }.string.toString()
                assertEquals("Only large windows select the rail layout: $layout", rail, layout.contains("layout-sw600dp-w600dp"))
                val grid = DashboardRecyclerView(context)
                fun measure(widthDp: Int) {
                    grid.measure(View.MeasureSpec.makeMeasureSpec(widthDp * density / 160, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(300 * density / 160, View.MeasureSpec.EXACTLY))
                }
                measure(width)
                assertEquals("Grid at ${width}dp, ${density}dpi", columns, grid.columnCount)
                if (rail) {
                    measure(580)
                    assertEquals("A narrow content pane uses two columns", 2, grid.columnCount)
                    measure(width)
                    assertEquals("Widening restores four columns", 4, grid.columnCount)
                }
                assertTrue(grid.measuredWidth > 0)
            }
        }
    }
}
