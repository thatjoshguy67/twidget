package com.tjg.twidget.ui

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardTweetTextInstrumentedTest {
    @Test fun partialLastLineIsReplacedByACompleteEllipsizedLine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val text = "For those wanting the iOS version of Twidget, this is it. Built with Twidget's backend, but with a clean, native iOS interface. Go grab it now in quoted tweet. ".repeat(3)
            val view = DashboardFadingTextView(context).apply {
                this.text = text
                textSize = 14f
                includeFontPadding = false
            }
            val width = (250 * context.resources.displayMetrics.density).toInt()
            val height = (view.lineHeight * 3.4f).toInt()
            fun measure(h: Int) {
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, h)
            }
            measure(height)
            val layout = requireNotNull(view.layout)
            assertTrue("Last visible line is complete", layout.getLineBottom(layout.lineCount-1) <= height)
            assertTrue("Truncation is explicit", layout.getEllipsisCount(layout.lineCount-1) > 0)
            assertEquals("Underlying tweet is preserved", text, view.text.toString())
            val compactLines = layout.lineCount
            measure(height * 2)
            assertTrue("Growing the card restores additional lines", view.layout.lineCount > compactLines)
        }
    }
}
