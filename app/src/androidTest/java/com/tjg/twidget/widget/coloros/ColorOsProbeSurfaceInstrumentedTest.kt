package com.tjg.twidget.widget.coloros

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.widget.ImageView
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.widget.TwidgetWidget
import com.tjg.twidget.widget.WidgetArtworkRenderer
import com.tjg.twidget.widget.WidgetPreviews
import com.tjg.twidget.widget.WidgetStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorOsProbeSurfaceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun onlyTheProbeHierarchyContainsTheTaggedSurface() {
        val inflater = LayoutInflater.from(context)
        val probe = inflater.inflate(R.layout.widget_coloros_host_blur, null)
        assertEquals("OplusBlurBg", probe.findViewById<View>(R.id.coloros_blur_target).tag)
        for (layout in listOf(R.layout.widget_blur, R.layout.widget_compact_square)) {
            val ordinary = inflater.inflate(layout, null)
            assertNull(ordinary.findViewWithTag<View>("OplusBlurBg"))
        }
    }

    @Test fun contentAndSpinnerStayInsideTheLauncherOwnedSurfaceAtEverySize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = LayoutInflater.from(context).inflate(R.layout.widget_coloros_host_blur, null)
            val surface = root.findViewById<ViewGroup>(R.id.coloros_blur_target)
            val art = root.findViewById<ImageView>(R.id.widget_artwork)
            assertSame(surface, art.parent)
            assertSame(surface, root.findViewById<View>(R.id.widget_loading).parent)
            assertTrue(surface.clipChildren)
            assertTrue(surface.clipToPadding)
            for ((w, h) in listOf(110 to 40, 162 to 176, 352 to 76, 352 to 176, 176 to 352)) {
                val density = context.resources.displayMetrics.density
                val width = (w * density).toInt()
                val height = (h * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                assertTrue(art.left >= surface.paddingLeft)
                assertTrue(art.top >= surface.paddingTop)
                assertTrue(art.right <= surface.width - surface.paddingRight)
                assertTrue(art.bottom <= surface.height - surface.paddingBottom)
                val bitmap = WidgetArtworkRenderer.render(context, width, height,
                    ProfileStats("Probe", "probe", 12345, 1, 1, 1), WidgetPreviews.settings(WidgetStyle.COLOR_OS),
                    TwidgetWidget.layoutModeForAosp(w, h), false, drawBackground = false)
                art.setImageBitmap(bitmap)
                val bounds = RectF(art.drawable.bounds)
                art.imageMatrix.mapRect(bounds)
                assertTrue(bounds.left >= -1f && bounds.top >= -1f)
                assertTrue(bounds.right <= art.width + 1f && bounds.bottom <= art.height + 1f)
                art.setImageDrawable(null)
                bitmap.recycle()
            }
        }
    }

    @Test fun reportRealProbeHostSizes() {
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, ColorOsWidgetBlurProbe.PROVIDER)).forEach { id ->
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                val options = manager.getAppWidgetOptions(id)
                putString("probe_allocation", "id=$id min=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)} max=${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)}x${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)} oneUiFlag=${com.tjg.twidget.ui.TwidgetFonts.hasSystemOneUiSans}")
            })
        }
    }

    @Test fun foregroundDoesNotPaintOverTheHostBlurSurface() {
        val stats = ProfileStats("Probe", "probe", 12345, 1, 1, 1)
        val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS)
        for (dark in listOf(false, true)) {
            val bitmap = WidgetArtworkRenderer.render(context, 360, 210, stats, settings,
                TwidgetWidget.LAYOUT_MODE_LARGE, dark, drawBackground = false)
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width / 2, 2)))
            bitmap.recycle()
        }
    }
}
