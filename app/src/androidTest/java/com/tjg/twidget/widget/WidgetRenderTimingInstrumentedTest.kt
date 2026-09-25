package com.tjg.twidget.widget

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** On-device characterization of the CPU work required by a resize callback. */
@RunWith(AndroidJUnit4::class)
class WidgetRenderTimingInstrumentedTest {
    @Test fun recordFollowerResizeRenderTimings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = InstrumentationRegistry.getArguments().getString("profileLabel", "current")
        val report = StringBuilder()
        val density = context.resources.displayMetrics.density
        for (font in listOf(TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_ONE_UI_SANS)) {
            val settings = TwidgetStore.widgetSettings(context).copy(fontFamily = font, style = WidgetStyle.MATERIAL)
            for ((width, height) in listOf(180 to 280, 352 to 176, 352 to 280)) {
                val timings = mutableListOf<Long>()
                repeat(4) { sample ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val bitmap = WidgetArtworkRenderer.render(context, (width * density).toInt(), (height * density).toInt(),
                        ProfileStats("Test", "thatjoshguy69", 7783, 0, 0, 0), settings,
                        TwidgetWidget.LAYOUT_MODE_LARGE, true, drawBackground = true)
                    timings += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                    if (sample == 0) File(context.cacheDir, "render-$label-$font-$width-$height.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                report.appendLine("$font ${width}x$height ms=$timings")
            }
        }
        File(context.cacheDir, "render-timing-$label.txt").writeText(report.toString())
    }
}
