package com.tjg.twidget.main

import android.graphics.Bitmap
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.ui.MediaAspectImageView
import com.tjg.twidget.ui.TweetMediaView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TweetMediaInstrumentedTest {
    @Test fun tallMediaCropsAtCapAndShortMediaKeepsAspectRatio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = MediaAspectImageView(instrumentation.targetContext).apply { maximumMediaHeightPx = 280 }
            val tall = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
            view.setImageBitmap(tall)
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals(280, view.measuredHeight)
            assertEquals(ImageView.ScaleType.CENTER_CROP, view.scaleType)
            val wide = Bitmap.createBitmap(80, 20, Bitmap.Config.ARGB_8888)
            view.setImageBitmap(wide)
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals(100, view.measuredHeight)
            assertEquals(ImageView.ScaleType.FIT_CENTER, view.scaleType)
            view.setImageDrawable(null)
            tall.recycle()
            wide.recycle()
        }
    }

    @Test fun multipleImagesUseOneScrollableStripWithAllAltText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val media = (1..3).map { PostMedia("photo", "", "Image $it", 80, 20) }
            val strip = TweetMediaView.create(instrumentation.targetContext, media) as HorizontalScrollView
            val row = strip.getChildAt(0) as LinearLayout
            assertEquals(3, row.childCount)
            assertFalse(strip.isHorizontalScrollBarEnabled)
            val bitmap = Bitmap.createBitmap(80, 20, Bitmap.Config.ARGB_8888)
            for (index in 0 until row.childCount) {
                assertEquals("Image ${index + 1}", row.getChildAt(index).contentDescription)
                assertTrue(row.getChildAt(index) is MediaAspectImageView)
                (row.getChildAt(index) as MediaAspectImageView).apply {
                    visibility = View.VISIBLE
                    setImageBitmap(bitmap)
                }
            }
            strip.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
            assertTrue("The remaining photos can be reached by horizontal scrolling", strip.canScrollHorizontally(1))
            assertTrue(TweetMediaView.create(instrumentation.targetContext, media.take(1)) is MediaAspectImageView)
        }
    }
}
