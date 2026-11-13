package com.tjg.twidget.main

import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.ui.DashboardGridLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveDashboardGridInstrumentedTest {
    @Test fun mixedSpansKeepEqualColumnsWhenResizing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val grid = DashboardGridLayout(instrumentation.targetContext)
            listOf(2, 1, 1, 2, 2).forEach { span ->
                grid.addView(FrameLayout(grid.context).apply { setTag(R.id.dashboard_card_span, span) },
                    GridLayout.LayoutParams().apply {
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, 1f)
                        width = 0; height = 214
                        setMargins(5, 5, 5, 5)
                    })
            }
            listOf(2 to 400, 4 to 900, 4 to 600, 4 to 800, 2 to 360).forEach { (columns, widthDp) ->
                val width = (widthDp * grid.resources.displayMetrics.density).toInt()
                grid.columnCount = columns
                grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                grid.layout(0, 0, width, grid.measuredHeight)
                val wide = grid.getChildAt(0)
                val small = grid.getChildAt(1)
                assertEquals("Four columns are retained at portrait Fold widths", columns, grid.columnCount)
                assertEquals("Narrow columns retain a readable native row height", (160 * grid.resources.displayMetrics.density).toInt(), wide.height)
                assertTrue(kotlin.math.abs((small.width + 10) * 2 - (wide.width + 10)) <= 1)
                for (i in 0 until grid.childCount) {
                    val card = grid.getChildAt(i)
                    assertEquals(wide.height, card.height)
                    assertTrue(card.left >= 0 && card.right <= width)
                }
            }
        }
    }
}
