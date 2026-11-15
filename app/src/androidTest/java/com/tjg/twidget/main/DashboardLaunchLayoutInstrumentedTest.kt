package com.tjg.twidget.main

import android.os.SystemClock
import android.view.ViewGroup
import android.view.View
import android.widget.GridLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.Stage
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.DashboardGridLayout
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardLaunchLayoutInstrumentedTest {
    @Test fun weightedCardsFitAfterProvisionalMeasurementsAndResizing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val grid = DashboardGridLayout(context).apply {
                    columnCount = 2
                    layoutDirection = direction
                }
                for (span in listOf(2, 1, 1, 2)) {
                    grid.addView(View(context), GridLayout.LayoutParams().apply {
                        width = 0
                        height = 100
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, 1f)
                        val margin = (5 * density).toInt()
                        setMargins(margin, margin, margin, margin)
                    })
                }
                for (widthDp in listOf(0, 392, 900, 600, 392)) {
                    val width = (widthDp * density).toInt()
                    grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    grid.layout(0, 0, width, grid.measuredHeight)
                    if (widthDp == 0) continue // The rail's provisional width cannot fit margins.
                    for (index in 0 until grid.childCount) {
                        val card = grid.getChildAt(index)
                        assertTrue("Card $index must fit after resize to $widthDp dp ($direction)",
                            card.left >= 0 && card.right <= width)
                    }
                    assertTrue("Half-width cards must remain equal", kotlin.math.abs(
                        grid.getChildAt(1).width - grid.getChildAt(2).width) <= 1)
                }
            }
        }
    }

    @Test fun cardsFitTheirGridFromTheFirstDraw() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(TwidgetStore.isOnboarded(context) && TwidgetStore.accounts(context).isNotEmpty())
        val frames = mutableListOf<String>()
        val violations = mutableListOf<String>()
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.CREATED) {
                val root = activity.window.decorView
                root.viewTreeObserver.addOnPreDrawListener {
                    val grid = activity.findViewById<com.tjg.twidget.ui.DashboardRecyclerView>(R.id.dashboard_content)
                    if (grid != null && frames.size < 30) {
                        val sizes = grid.dashboardCardChildren().map { child ->
                            val card = child as ViewGroup
                            val content = card.getChildAt(0)
                            if (content.width > card.width || card.right > grid.width - grid.paddingRight) {
                                violations += "frame=${frames.size}, card=${card.tag}, grid=${grid.width}, " +
                                    "card=${card.width}/${card.right}, content=${content.width}"
                            }
                            "${card.tag}=${card.width}/${content.width}"
                        }
                        frames += "grid=${grid.width}, window=${root.width}, cards=$sizes"
                    }
                    true
                }
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
                scenario.onActivity {
                    File(context.cacheDir, "launch-layout-frames.txt").writeText(frames.joinToString("\n"))
                    assertTrue("The first dashboard frame must be measured", frames.isNotEmpty())
                    assertTrue("Dashboard content must fit from first draw: ${violations.take(5)}", violations.isEmpty())
                }
            }
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) }
        }
    }
}
