package com.tjg.twidget.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import androidx.appcompat.widget.SeslProgressBar
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.main.AboutActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadingSpinnerInstrumentedTest {
    @Test fun gradientDotOpacityNeverOvershoots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Despite the exported resource name, the APK uses controlY1=1.0.
        // Reconstructing it as 1.399 made the two dots overshoot full opacity.
        val easing = AnimationUtils.loadInterpolator(context,
            R.anim.oneui85_easing_custom_0_497_1_399_0_605_1_000)
        for (step in 0..1000) {
            val alpha = 0.7f + 0.3f * easing.getInterpolation(step / 1000f)
            assertTrue("Dot alpha must stay within its visible range at step $step: $alpha", alpha in 0.7f..1f)
        }
    }

    @Test fun screenSpinnersRepeatAndStopWhenTheirParentIsHidden() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var panel: LinearLayout
            val spinners = mutableListOf<SeslProgressBar>()
            scenario.onActivity { activity ->
                panel = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 120, 0, 0)
                    setBackgroundColor(Color.WHITE)
                }
                for ((layout, id) in listOf(
                    R.layout.activity_about to R.id.about_update_spinner,
                    R.layout.activity_import_analytics to R.id.import_spinner,
                )) {
                    val root = activity.layoutInflater.inflate(layout, FrameLayout(activity), false)
                    val spinner = root.findViewById<SeslProgressBar>(id)
                    (spinner.parent as ViewGroup).removeView(spinner)
                    spinner.visibility = View.VISIBLE
                    panel.addView(spinner)
                    spinners += spinner
                }
                activity.addContentView(panel, ViewGroup.LayoutParams(360, 1000))
            }
            // Check beyond several complete cycles, then exercise parent visibility
            // (the import flow hides its whole loading step, rather than the spinner).
            repeat(3) {
                SystemClock.sleep(2300)
                scenario.onActivity {
                    spinners.forEach { spinner ->
                        assertTrue("Visible spinner must keep animating", (spinner.indeterminateDrawable as Animatable).isRunning)
                    }
                }
            }
            scenario.onActivity { panel.visibility = View.GONE }
            SystemClock.sleep(250)
            scenario.onActivity {
                spinners.forEach { assertFalse((it.indeterminateDrawable as Animatable).isRunning) }
                panel.visibility = View.VISIBLE
            }
            SystemClock.sleep(250)
            scenario.onActivity {
                spinners.forEach { assertTrue((it.indeterminateDrawable as Animatable).isRunning) }
            }
        }
    }

    @Test fun swipeRefreshOwnsAndStopsItsNativeAnimation() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            lateinit var refresh: SwipeRefreshLayout
            lateinit var drawable: CircularProgressDrawable
            scenario.onActivity { activity ->
                refresh = activity.findViewById(R.id.about_refresh)
                val circle = (0 until refresh.childCount).map(refresh::getChildAt)
                    .filterIsInstance<android.widget.ImageView>().first()
                drawable = circle.drawable as CircularProgressDrawable
                refresh.isRefreshing = true
            }
            SystemClock.sleep(700)
            scenario.onActivity { assertTrue(drawable.isRunning); refresh.isRefreshing = false }
            SystemClock.sleep(700)
            scenario.onActivity { assertFalse(drawable.isRunning) }
        }
    }

    @Test fun launcherCanInflateAndDrawEveryWidgetSpinnerWithoutTheAppTheme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, android.R.style.Theme_Material_Light)
            for (layout in listOf(R.layout.widget_blur, R.layout.widget_compact_square,
                R.layout.widget_compact_2x1, R.layout.widget_compact_strip)) {
                val root = RemoteViews(context.packageName, layout).apply(context, FrameLayout(context))
                val spinner = root.findViewById<ProgressBar>(R.id.widget_loading)
                val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                try {
                    spinner.indeterminateDrawable.apply {
                        setBounds(0, 0, 100, 100)
                        draw(Canvas(bitmap))
                    }
                    val pixels = IntArray(10000)
                    bitmap.getPixels(pixels, 0, 100, 0, 0, 100, 100)
                    assertTrue("Widget spinner must resolve its colors in launcher context", pixels.any { Color.alpha(it) > 0 })
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }
}
