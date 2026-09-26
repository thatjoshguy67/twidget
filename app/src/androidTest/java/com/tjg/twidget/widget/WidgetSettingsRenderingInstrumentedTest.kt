package com.tjg.twidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.widget.SwitchItemView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSettingsRenderingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun wallpaperScrollStaysOutsideNativeFadeLayers() {
        ActivityScenario.launch(WidgetConfigActivity::class.java).use { scenario ->
            repeat(2) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<NestedScrollView>(R.id.widget_settings_scroll)
                    // API 36 One UI defaults this to transparent, which adds
                    // isolated layers. Emulate that platform default explicitly.
                    scroll.seslSetFadingEdgeColor(Color.TRANSPARENT)
                    scroll.scrollTo(0, 300)
                    scroll.requestLayout()
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<NestedScrollView>(R.id.widget_settings_scroll)
                    assertFalse("Transparent wallpaper openings cannot be nested in SESL fade layers",
                        scroll.seslIsFadingEdgeEnabled())
                    scroll.scrollTo(0, 0)
                }
                scenario.recreate()
            }
        }
    }

    @Test fun wallpaperRemainsVisibleWhilePullingPastTop() {
        ActivityScenario.launch(WidgetConfigActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            SystemClock.sleep(700)
            val bounds = Rect()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.preview_container).getGlobalVisibleRect(bounds))
            }
            // Sample the gutter, where the stationary wallpaper stays visible
            // throughout the gesture even if the content stretches slightly.
            val x = bounds.left + bounds.width() / 12
            val y = bounds.centerY()
            val before = instrumentation.uiAutomation.takeScreenshot()
            val baseline = before.getPixel(x, y)
            before.recycle()
            val downTime = SystemClock.uptimeMillis()
            var touchY = y.toFloat()
            fun touch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x.toFloat(), touchY, 0)
                try {
                    scenario.onActivity { it.window.decorView.dispatchTouchEvent(event) }
                } finally { event.recycle() }
            }
            fun checkWallpaper() {
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try {
                    val actual = screenshot.getPixel(x, y)
                    assertTrue("Wallpaper disappeared during top overscroll: $baseline -> $actual", maxOf(
                        kotlin.math.abs(Color.red(baseline) - Color.red(actual)),
                        kotlin.math.abs(Color.green(baseline) - Color.green(actual)),
                        kotlin.math.abs(Color.blue(baseline) - Color.blue(actual))) < 12)
                } finally { screenshot.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN)
            try {
                repeat(8) {
                    touchY += bounds.height() / 20f
                    SystemClock.sleep(30)
                    touch(MotionEvent.ACTION_MOVE)
                }
                SystemClock.sleep(100)
                checkWallpaper()
            } finally {
                touch(MotionEvent.ACTION_UP)
            }
            SystemClock.sleep(50)
            checkWallpaper()
            SystemClock.sleep(700)
            checkWallpaper()
        }
    }

    @Test fun settingsCardsUseNativeMeasurements() {
        ActivityScenario.launch(WidgetConfigActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                for (id in listOf(R.id.widget_style_row, R.id.font_row, R.id.language_row, R.id.tint_row, R.id.logo_row)) {
                    val actual = activity.findViewById<CardItemView>(id)
                    val native = CardItemView(activity).apply {
                        title = actual.title
                        summary = actual.summary
                        showTopDivider = actual.showTopDivider
                    }
                    com.tjg.twidget.ui.TwidgetFonts.applyTo(native)
                    val width = View.MeasureSpec.makeMeasureSpec(actual.width, View.MeasureSpec.EXACTLY)
                    val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    native.measure(width, height)
                    actual.measure(width, height)
                    assertEquals("Card height must come from SESL: ${activity.resources.getResourceEntryName(id)}", native.measuredHeight, actual.measuredHeight)
                }
                assertTrue(activity.findViewById<View>(R.id.delta_row) is SwitchItemView)
                assertTrue(activity.findViewById<View>(R.id.contained_footer_row) is SwitchItemView)
                val accounts = activity.findViewById<LinearLayout>(R.id.account_group)
                if (accounts.childCount > 0) assertEquals(
                    activity.resources.getDimensionPixelSize(R.dimen.settings_account_min_height),
                    accounts.getChildAt(0).minimumHeight)
            }
        }
    }

    @Test fun wallpaperOpeningKeepsItsFullContourWhenPartiallyClipped() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val view = WallpaperPreviewLayout(context)
            val width = (392 * context.resources.displayMetrics.density).toInt()
            val height = (236 * context.resources.displayMetrics.density).toInt()
            view.layout(0, 0, width, height)
            val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(full).apply { drawColor(Color.BLACK); view.draw(this) }
            assertEquals(0, Color.alpha(full.getPixel(width / 2, 2)))
            assertEquals(255, Color.alpha(full.getPixel(0, 0)))
            val clipped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(clipped).apply {
                drawColor(Color.BLACK)
                clipRect(0, height / 3, width, height)
                view.draw(this)
            }
            // Clipping must not invent new corners at the scroll viewport boundary.
            for (y in height / 3 until height) for (x in 0 until width) {
                assertEquals("Contour moved at $x,$y", full.getPixel(x, y), clipped.getPixel(x, y))
            }
            full.recycle()
            clipped.recycle()
        }
    }

    @Test fun wallpaperPreviewSurvivesRepeatedHardwareScrolls() {
        ActivityScenario.launch(WidgetConfigActivity::class.java).use { scenario ->
            fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(700) }
            fun capture(): Pair<Rect, Bitmap> {
                val bounds = Rect()
                scenario.onActivity { it.findViewById<View>(R.id.preview_container).getGlobalVisibleRect(bounds) }
                return bounds to instrumentation.uiAutomation.takeScreenshot()
            }
            settle()
            val (beforeBounds, before) = capture()
            repeat(4) {
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<NestedScrollView>(R.id.widget_settings_scroll)
                    scroll.scrollTo(0, activity.findViewById<View>(R.id.preview_container).height / 2)
                }
                settle()
                scenario.onActivity { it.findViewById<NestedScrollView>(R.id.widget_settings_scroll).scrollTo(0, 10000) }
                settle()
                scenario.onActivity { it.findViewById<NestedScrollView>(R.id.widget_settings_scroll).scrollTo(0, 0) }
                settle()
                val (afterBounds, after) = capture()
                assertEquals("Preview must return to its original position", beforeBounds, afterBounds)
                // Sample the wallpaper gutter alongside the widget, away from its artwork
                // and the native rounded corners. This catches stale opaque bands.
                val x = beforeBounds.left + beforeBounds.width() / 12
                for (part in 2..8) {
                    val y = beforeBounds.top + beforeBounds.height() * part / 10
                    val a = before.getPixel(x, y)
                    val b = after.getPixel(x, y)
                    assertTrue("Wallpaper changed after scroll at $x,$y", maxOf(
                        kotlin.math.abs(Color.red(a) - Color.red(b)),
                        kotlin.math.abs(Color.green(a) - Color.green(b)),
                        kotlin.math.abs(Color.blue(a) - Color.blue(b))) < 12)
                }
                after.recycle()
            }
            before.recycle()
        }
    }
}
