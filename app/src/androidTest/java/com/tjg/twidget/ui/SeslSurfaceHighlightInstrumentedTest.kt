package com.tjg.twidget.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SeslProgressBar
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.oneui.floatingactioncontainer.FloatingGroupLayout
import com.tjg.twidget.R
import com.tjg.twidget.main.AboutActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeslSurfaceHighlightInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun rimFadesDownwardAndLeavesInteriorClearInBothThemes() {
        for (light in listOf(true, false)) {
            val rim = SeslRimDrawable(2f, 24f, light)
            for (width in listOf(80, 240)) {
                val bitmap = Bitmap.createBitmap(width, 100, Bitmap.Config.ARGB_8888)
                rim.setBounds(0, 0, width, 100)
                rim.draw(Canvas(bitmap))
                val top = Color.alpha(bitmap.getPixel(width / 2, 1))
                val middle = Color.alpha(bitmap.getPixel(1, 50))
                assertTrue("Upper rim must be visible", top > if (light) 150 else 15)
                assertTrue("Rim fades toward the bottom", middle < top)
                assertEquals(0, Color.alpha(bitmap.getPixel(width / 2, 50)))
                assertEquals(0, Color.alpha(bitmap.getPixel(width / 2, 99)))
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                bitmap.recycle()
            }
        }
    }

    @Test fun preservesForegroundAndDoesNotStackOnRepeatedDiscovery() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            val original = ColorDrawable(Color.RED)
            val view = View(context).apply { foreground = original }
            SeslSurfaceHighlight.attach(view, 24f)
            val combined = view.foreground as LayerDrawable
            assertSame(original, combined.getDrawable(0))
            repeat(5) { SeslSurfaceHighlight.attach(view, 24f) }
            assertSame(combined, view.foreground)
            SeslSurfaceHighlight.remove(view)
            assertSame(original, view.foreground)
            SeslSurfaceHighlight.attach(view, 24f)
            val replacement = ColorDrawable(Color.BLUE)
            view.foreground = replacement
            SeslSurfaceHighlight.remove(view)
            assertSame("A later owner retains its foreground", replacement, view.foreground)
        }
    }

    @Test fun circularTrackUsesThemeColorAndPreservesExplicitTint() {
        instrumentation.runOnMainSync {
            for (light in listOf(true, false)) {
                val context = ContextThemeWrapper(instrumentation.targetContext, if (light)
                    androidx.appcompat.R.style.Theme_AppCompat_Light else androidx.appcompat.R.style.Theme_AppCompat)
                val progress = SeslProgressBar(context).apply {
                    isIndeterminate = false
                    setMode(SeslProgressBar.MODE_CIRCLE)
                }
                SeslSurfaceHighlight.applyTo(progress)
                val color = context.getColor(if (light)
                    androidx.appcompat.R.color.sesl_progress_control_color_background_light
                else androidx.appcompat.R.color.sesl_progress_control_color_background_dark)
                assertEquals(color, progress.progressBackgroundTintList?.defaultColor)
                progress.progressBackgroundTintList = ColorStateList.valueOf(Color.MAGENTA)
                SeslSurfaceHighlight.applyTo(progress)
                assertEquals(Color.MAGENTA, progress.progressBackgroundTintList?.defaultColor)
                val spinner = SeslProgressBar(context).apply { isIndeterminate = true }
                SeslSurfaceHighlight.applyTo(spinner)
                assertNull(spinner.progressBackgroundTintList)
            }
        }
    }

    @Test fun discoversFloatingSurfacesAndDialogPanels() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                SeslSurfaceHighlight.applyTo(root)
                val surfaces = descendants(root).filterIsInstance<
                    FloatingGroupLayout.SeslProjectionView.SeslProjectionBackgroundView>().toList()
                assertTrue("SESL floating backgrounds exist", surfaces.isNotEmpty())
                surfaces.forEach { assertNotNull(it.getTag(R.id.sesl_surface_highlight)) }
                val dialog = AlertDialog.Builder(activity).setTitle("Surface check")
                    .setMessage("Dialog foreground integration").setPositiveButton("OK", null).show()
                try {
                    SeslSurfaceHighlight.applyTo(dialog.window!!.decorView)
                    assertNotNull(dialog.findViewById<View>(androidx.appcompat.R.id.parentPanel)
                        ?.getTag(R.id.sesl_surface_highlight))
                } finally { dialog.dismiss() }
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }
}
