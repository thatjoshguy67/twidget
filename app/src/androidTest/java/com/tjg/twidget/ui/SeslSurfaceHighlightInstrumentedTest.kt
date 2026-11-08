package com.tjg.twidget.ui

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SeslProgressBar
import androidx.appcompat.widget.SearchView
import android.widget.FrameLayout
import androidx.test.filters.SdkSuppress
import android.view.inspector.WindowInspector
import com.google.android.material.oneui.dividerbuttonlayout.DividerButtonLayout
import com.google.android.material.oneui.responsivepane.ResponsiveDrawerLayout
import com.google.android.material.snackbar.SnackbarContentLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
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

    @Test fun blurPanelWithRectangularOutlineStillGetsRoundedRim() {
        val background = ColorDrawable(Color.GRAY).apply { setBounds(0, 0, 160, 100) }
        val rim = SeslRimDrawable(2f, 26f, true) { background }
        rim.setBounds(0, 0, 160, 100)
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        rim.draw(Canvas(bitmap))
        assertTrue("A blur panel's plain fill must not suppress its rim", Color.alpha(bitmap.getPixel(80, 1)) > 150)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        bitmap.recycle()
    }

    @Test fun insetOvalAndIndependentCornersFollowTheirBackground() {
        for (oval in listOf(true, false)) {
            val shape = GradientDrawable().apply {
                setColor(Color.GRAY)
                if (oval) this.shape = GradientDrawable.OVAL
                else cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
            }
            val background = InsetDrawable(shape, 10).apply { setBounds(0, 0, 120, 120) }
            val rim = SeslRimDrawable(2f, 26f, true) { background }
            rim.setBounds(0, 0, 120, 120)
            val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            rim.draw(Canvas(bitmap))
            assertEquals("Inset area remains clear", 0, Color.alpha(bitmap.getPixel(60, 1)))
            assertTrue("Rim follows the inset surface", Color.alpha(bitmap.getPixel(60, 11)) > 150)
            assertEquals(0, Color.alpha(bitmap.getPixel(11, 11)))
            assertEquals(0, Color.alpha(bitmap.getPixel(60, 60)))
            bitmap.recycle()
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    @Test fun discoversActionBarsTabsSnackbarsDrawerSearchAndCustomCapsules() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            val root = FrameLayout(context)
            val surfaces = listOf(DividerButtonLayout(context), ResponsiveDrawerLayout(context),
                SnackbarContentLayout(context), BottomNavigationView(context).apply {
                    background = GradientDrawable().apply { cornerRadius = 26f }
                }, TabLayout(context).apply { background = GradientDrawable().apply { cornerRadius = 26f } },
                View(context).apply { id = R.id.brief_footer_info },
                View(context).apply { id = com.google.android.material.R.id.design_bottom_sheet },
                View(context).apply { SeslSurfaceHighlight.mark(this, 26f) })
            surfaces.forEach(root::addView)
            val search = SearchView(context)
            root.addView(search)
            SeslSurfaceHighlight.applyTo(root)
            surfaces.forEach { assertNotNull(it.javaClass.name, it.getTag(R.id.sesl_surface_highlight)) }
            val plate = search.findViewById<View>(androidx.appcompat.R.id.search_plate_frame)
            assertNotNull("Search frame owns the rim", plate.getTag(R.id.sesl_surface_highlight))
            assertNull("Search content must not get a second rim",
                search.findViewById<View>(androidx.appcompat.R.id.search_plate).getTag(R.id.sesl_surface_highlight))
        }
    }

    @Test fun noticeBackHostIsNotOutlinedAlongsideNativeButtonSurface() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            val host = FrameLayout(context).apply {
                id = R.id.notice_detail_back
                layoutParams = FrameLayout.LayoutParams(400, 56)
            }
            com.tjg.twidget.notices.NoticeReaderChrome.install(host) { }
            SeslSurfaceHighlight.applyTo(host)
            assertNull("The full-width toolbar host has no surface of its own", host.foreground)
            val surfaces = descendants(host).filterIsInstance<
                FloatingGroupLayout.SeslProjectionView.SeslProjectionBackgroundView>().toList()
            assertTrue(surfaces.isNotEmpty())
            surfaces.forEach { assertNotNull(it.getTag(R.id.sesl_surface_highlight)) }
        }
    }

    @Test fun legacyDrawerPanelRetainsItsNativeUnoutlinedSurface() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            val panel = FrameLayout(context).apply {
                id = dev.oneuiproject.oneui.design.R.id.drawer_panel
                background = ColorDrawable(Color.GRAY)
            }
            val nativeForeground = ColorDrawable(Color.TRANSPARENT)
            panel.foreground = nativeForeground
            // Also exercise cleanup if a caller previously decorated the host.
            SeslSurfaceHighlight.attach(panel, 26f)
            repeat(3) { SeslSurfaceHighlight.applyTo(panel) }
            assertSame(nativeForeground, panel.foreground)
            assertNull(panel.getTag(R.id.sesl_surface_highlight))
        }
    }


    @SdkSuppress(minSdkVersion = 29)
    @Test fun platformSettingsDropdownGetsWholeWindowSurfaceRim() {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            var popup: android.widget.ListPopupWindow? = null
            scenario.onActivity { activity ->
                popup = com.tjg.twidget.settings.showSettingsDropdown(
                    activity.findViewById(R.id.about_toolbar), listOf("First", "Second")) { }
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                try {
                    val list = requireNotNull(popup?.listView)
                    assertTrue(WindowInspector.getGlobalWindowViews().contains(list.rootView))
                    val ancestors = generateSequence(list as View) { view -> view.parent as? View }.toList()
                    assertTrue("Native popup must be observed and styled", ancestors.any { view ->
                        view.getTag(R.id.sesl_surface_highlight) != null
                    })
                    assertTrue("Only one rim encloses the popup", ancestors.count { view ->
                        view.getTag(R.id.sesl_surface_highlight) != null
                    } == 1)
                } finally { popup?.dismiss() }
            }
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
