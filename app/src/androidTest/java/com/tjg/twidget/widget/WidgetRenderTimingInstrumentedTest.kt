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
    @Test fun recordLightAndDarkResizeSequenceTimings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = InstrumentationRegistry.getArguments().getString("profileLabel", "current")
        val report = StringBuilder()
        for (font in listOf(TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_SYSTEM)) {
            val settings = TwidgetStore.widgetSettings(context).copy(fontFamily = font,
                style = WidgetStyle.ONE_UI, colorMode = TwidgetStore.COLOR_MODE_SYSTEM, showDelta = true)
            repeat(2) { pass ->
                val timings = mutableListOf<Long>()
                for ((width, height) in listOf(352 to 176, 180 to 280, 352 to 76, 162 to 76, 180 to 176, 352 to 280, 352 to 176)) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    val views = TwidgetWidget.createRemoteViews(context, 97542, width, height,
                        TwidgetWidget.layoutMode(width, height), settings, "test",
                        ProfileStats("Test", "test", 7795, 0, 0, 0), -1, false)
                    val parcel = android.os.Parcel.obtain()
                    try { views.writeToParcel(parcel, 0) } finally { parcel.recycle() }
                    timings += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
                }
                report.appendLine("$font pass=$pass light+dark RemoteViews ms=$timings")
            }
        }
        File(context.cacheDir, "resize-remoteviews-$label.txt").writeText(report.toString())
    }

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
