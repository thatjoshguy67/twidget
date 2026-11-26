package com.tjg.twidget.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MetricChartTooltipInstrumentedTest {
    @Test fun longLossTooltipsFitNarrowPlotsAndLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.5f, 2f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply { this.fontScale = fontScale }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                for (widthDp in listOf(100, 180, 240, 480)) {
                    val chart = MetricChartView(context).apply {
                        compactCardInsets = true
                        setOptionalSeries((20..26).map { "$it Nov" to 8074L })
                        setUnfollowSeries(List(7) { 10L }, List(7) { "~10 lost · November 25–26, 2026 · observed follower membership changes" })
                    }
                    val density = context.resources.displayMetrics.density
                    val width = (widthDp * density).roundToInt()
                    val height = (144 * density).roundToInt()
                    chart.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    chart.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        chart.draw(Canvas(bitmap))
                        @Suppress("UNCHECKED_CAST")
                        val bars = field(chart, "barHitBounds") as List<RectF>
                        for (bar in listOf(bars.first(), bars.last())) {
                            val hover = MotionEvent.obtain(0, 0, MotionEvent.ACTION_HOVER_MOVE, bar.centerX(), bar.centerY(), 0)
                            try { chart.onHoverEvent(hover) } finally { hover.recycle() }
                            chart.draw(Canvas(bitmap)) // Previously threw coerceIn's empty-range exception.
                            val tooltip = field(chart, "tooltipRect") as RectF
                            val plot = field(chart, "plotRect") as RectF
                            assertTrue("Tooltip has visible width at $widthDp / $fontScale", tooltip.width() > 0)
                            assertTrue(tooltip.left >= plot.left - 1)
                            assertTrue(tooltip.right <= plot.right + 1)
                            assertTrue(tooltip.top >= 0 && tooltip.bottom <= height + 1)
                        }
                        assertTrue(chart.contentDescription.contains("~10 lost"))
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private fun field(chart: MetricChartView, name: String): Any =
        MetricChartView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(chart)!!
}
