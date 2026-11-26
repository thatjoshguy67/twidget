package com.tjg.twidget.brief

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.StreakSnapshot
import com.tjg.twidget.ui.BriefDashboardCardLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefHomeCardInstrumentedTest {
    @Test fun contentChangesTheGlowButKeepsTheGreySurfaceAndDescriptiveCopy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (dark in listOf(false, true)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val card = LayoutInflater.from(context).inflate(R.layout.brief_dashboard_card, null) as BriefDashboardCardLayout
                card.findViewById<TextView>(R.id.brief_dashboard_title).text = "A moment to reset"
                val description = BriefHomeCardCopy.description(context, BriefCardType.STREAK, StreakSnapshot(0, false, null))
                assertTrue(description.isNotBlank())
                assertFalse(Regex("[+−-]\\d+").containsMatchIn(description))
                card.findViewById<TextView>(R.id.brief_dashboard_message).text = description
                val width = (352 * context.resources.displayMetrics.density).toInt()
                val height = (160 * context.resources.displayMetrics.density).toInt()
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                card.layout(0, 0, width, height)
                fun render(streak: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    card.setGlowType(BriefCardType.STREAK, 3, -21, streak)
                    card.draw(Canvas(it))
                }
                val reset = render(0)
                val growing = render(6)
                assertFalse("Identical card copy gets different measured content glows", reset.sameAs(growing))
                val pixels = IntArray(width * height)
                reset.getPixels(pixels, 0, width, 0, 0, width, height)
                val neutral = pixels.count {
                    val channels = listOf(Color.red(it), Color.green(it), Color.blue(it))
                    Color.alpha(it) > 200 && channels.max() - channels.min() < 10
                }
                assertTrue("The grey surface remains visible", neutral > pixels.size / 10)
                java.io.File(context.getExternalFilesDir(null), "brief-home-description-$dark.png").outputStream().use {
                    reset.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                reset.recycle(); growing.recycle()
            }
        }
    }
}
