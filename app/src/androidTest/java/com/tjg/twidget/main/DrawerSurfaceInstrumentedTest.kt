package com.tjg.twidget.main

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.SemBlurCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.AppAppearance
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect every rendered transition frame, rather than only the settled drawer. */
@RunWith(AndroidJUnit4::class)
class DrawerSurfaceInstrumentedTest {
    @Test fun nonBlurDrawerStaysOpaqueInLightMode() = checkSurface(AppCompatDelegate.MODE_NIGHT_NO)
    @Test fun nonBlurDrawerStaysOpaqueInDarkMode() = checkSurface(AppCompatDelegate.MODE_NIGHT_YES)

    private fun checkSurface(mode: Int) {
        assumeFalse(SemBlurCompat.isBlurEffectPresetSupport())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val saved = prefs.all
        val appearance = AppAppearance.mode(context)
        try {
            prefs.edit().putString("username", "drawer_fixture").putBoolean("onboarded", true)
                .putBoolean("refresh_on_launch", false).commit()
            instrumentation.runOnMainSync { AppAppearance.setMode(context, mode) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(500)
                for (open in listOf(true, false, true, false)) {
                    val frames = mutableListOf<Pair<Int, Int>>()
                    lateinit var surface: View
                    lateinit var listener: ViewTreeObserver.OnPreDrawListener
                    scenario.onActivity { activity ->
                        surface = activity.findViewById(com.google.android.material.R.id.responsive_pane_drawer)
                        listener = ViewTreeObserver.OnPreDrawListener {
                            val pane = surface.parent as? com.google.android.material.oneui.responsivepane.ResponsivePaneLayout
                            val railWidth = pane?.getPaneConfig(com.google.android.material.oneui.responsivepane.model.PaneState.CLOSED)?.width
                            val expected = if (railWidth != null && surface.width <= railWidth) 0 else 255
                            frames.add(surface.background.alpha to expected)
                            true
                        }
                        surface.viewTreeObserver.addOnPreDrawListener(listener)
                        activity.drawerController.setDrawerOpen(open)
                    }
                    SystemClock.sleep(750)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        surface.viewTreeObserver.removeOnPreDrawListener(listener)
                        assertEquals(open, activity.drawerController.isDrawerOpen)
                        assertTrue("Animated transition produces multiple visible frames: $frames", frames.size >= 3)
                        assertTrue("Only the collapsed rail is transparent during ${if (open) "opening" else "closing"}: $frames", frames.all { it.first == it.second })
                        val rail = surface.parent is com.google.android.material.oneui.responsivepane.ResponsivePaneLayout
                        assertEquals("Collapsed rails are transparent; expanded surfaces are solid", if (rail && !open) 0 else 255, surface.background.alpha)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppAppearance.setMode(context, appearance) }
            prefs.edit().clear().apply {
                saved.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
    }
}
