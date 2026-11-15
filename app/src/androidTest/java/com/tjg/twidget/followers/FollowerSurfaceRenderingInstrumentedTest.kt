package com.tjg.twidget.followers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowerSurfaceRenderingInstrumentedTest {
    @Test fun scrollingDrawsContinuousEdgesUntilTheRealLastRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            val list = FollowerRecyclerView(context)
            val manager = LinearLayoutManager(context)
            list.layoutManager = manager
            list.seslSetFillBottomEnabled(false)
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 100
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(View(context).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
            fun layoutAt(position: Int) {
                manager.scrollToPositionWithOffset(position, 0)
                list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                list.layout(0, 0, 400, 600)
            }
            fun draw(): Bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).also {
                list.draw(Canvas(it))
            }
            layoutAt(40)
            repeat(12) {
                list.scrollBy(0, 35)
                // Draw immediately: no pre-draw callback to repair stale geometry.
                draw().also { frame ->
                    assertEquals("Bottom corner during scrolling", 255, Color.alpha(frame.getPixel(0, 599)))
                    assertEquals("Top corner during scrolling", 255, Color.alpha(frame.getPixel(0, 0)))
                    frame.recycle()
                }
            }
            list.stopScroll()
            draw().also {
                assertEquals("Idle has the same continuous edge", 255, Color.alpha(it.getPixel(0, 599)))
                it.recycle()
            }
            layoutAt(99)
            draw().also {
                assertTrue("Real content end retains its rounded corner", Color.alpha(it.getPixel(0, 599)) < 255)
                it.recycle()
            }
        }
    }
}
