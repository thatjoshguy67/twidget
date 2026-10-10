package com.tjg.twidget.ui

import android.graphics.Bitmap
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAspectImageViewInstrumentedTest {
    @Test fun mediaUsesOriginalRatioForFullWidthCardsAndFixedHeightGalleries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val image = MediaAspectImageView(instrumentation.targetContext)
            for ((width, height) in listOf(400 to 200, 200 to 400, 300 to 300)) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                image.setImageBitmap(bitmap)
                image.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertEquals(600 * height / width, image.measuredHeight)
                image.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(218, View.MeasureSpec.EXACTLY))
                assertEquals((218f * width / height).toInt(), image.measuredWidth)
                bitmap.recycle()
            }
        }
    }
}
