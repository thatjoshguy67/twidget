package com.tjg.twidget.widget.coloros

import android.graphics.Color
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
