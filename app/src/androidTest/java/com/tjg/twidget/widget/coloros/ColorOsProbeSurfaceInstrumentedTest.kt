package com.tjg.twidget.widget.coloros

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.main.MainActivity
import com.tjg.twidget.widget.ColorOsNativeFollowerViews
import com.tjg.twidget.widget.TwidgetWidget
import com.tjg.twidget.widget.WidgetPreviews
import com.tjg.twidget.widget.WidgetStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorOsProbeSurfaceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeForegroundAndBlurHaveSeparateEffectScopesAndSharedBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tap = PendingIntent.getActivity(context, 776,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val root = ColorOsNativeFollowerViews.create(context, 330, 157,
                WidgetPreviews.settings(WidgetStyle.COLOR_OS), ProfileStats("Probe", "probe", 7935, 1, 1, 1), 8, tap)
                .apply(context, FrameLayout(context)) as ViewGroup
            val surface = root.findViewById<ViewGroup>(R.id.coloros_blur_target)
            val foreground = root.findViewById<View>(R.id.coloros_native_content)
            assertEquals("OplusBlurBg", surface.tag)
            assertEquals(0, surface.childCount)
            assertSame(root, surface.parent)
            assertSame(root, foreground.parent)
            assertTrue(root.clipToOutline)
            val outline = Outline()
            root.background.getOutline(outline)
            assertTrue(outline.canClip())
            assertNull(root.findViewById<View>(R.id.widget_artwork))
            assertTrue(root.findViewById<TextView>(R.id.coloros_native_count).autoSizeTextType != TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        }
    }

    @Test fun nativeTextAndFooterFitResizedCardsWithLargeCountsAndFontScales() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sizes = listOf(110 to 40, 162 to 176, 330 to 157, 352 to 76, 352 to 176, 176 to 352, 120 to 121)
            for (fontScale in listOf(1f, 1.5f)) for (dark in listOf(false, true)) {
                val config = Configuration(context.resources.configuration).apply {
                    this.fontScale = fontScale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val themed = context.createConfigurationContext(config)
                val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS).copy(showDelta = true)
                val tap = PendingIntent.getActivity(context, 777, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                for ((w, h) in sizes) {
                    val views = ColorOsNativeFollowerViews.create(themed, w, h, settings,
                        ProfileStats("Probe", "averylongaccountname", Long.MAX_VALUE, 1, 1, 1), 999999999, tap)
                    val root = views.apply(themed, FrameLayout(themed)) as ViewGroup
                    val density = themed.resources.displayMetrics.density
                    val width = (w * density).toInt()
                    val height = (h * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    val surface = root.findViewById<View>(R.id.coloros_blur_target)
                    assertEquals(width, surface.width)
                    assertEquals(height, surface.height)
                    for (id in listOf(R.id.coloros_native_count, R.id.coloros_native_label, R.id.coloros_native_delta,
                        R.id.coloros_native_account, R.id.coloros_native_logo)) {
                        val child = root.findViewById<View>(id)
                        if (child.visibility == View.GONE) continue
                        val rect = Rect(0, 0, child.width, child.height)
                        root.offsetDescendantRectToMyCoords(child, rect)
                        assertTrue("$w x $h font=$fontScale rect=$rect", rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height)
                    }
                    val count = root.findViewById<TextView>(R.id.coloros_native_count)
                    assertTrue(count.text.isNotEmpty())
                    assertTrue("text height=${count.layout.height} box=${count.height}", count.layout.height <= count.height + 1)
                    assertEquals(if (dark) Color.WHITE else 0xFF18202A.toInt(), count.currentTextColor)
                    assertEquals(View.GONE, root.findViewById<View>(R.id.widget_loading).visibility)
                }
            }
        }
    }

    @Test fun ordinaryStylesKeepTheirOriginalBitmapRendererWithoutBlurTags() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val stats = ProfileStats("Probe", "probe", 7935, 1, 1, 1)
            for (style in WidgetStyle.entries) {
                val views = TwidgetWidget.createRemoteViews(context, -1, 330, 157, TwidgetWidget.LAYOUT_MODE_LARGE,
                    WidgetPreviews.settings(style), "probe", stats, 8, true)
                val root = views.apply(context, FrameLayout(context))
                assertNotNull(root.findViewById<View>(R.id.widget_artwork))
                assertNull(root.findViewWithTag<View>("OplusBlurBg"))
                assertNull(root.findViewById<View>(R.id.coloros_native_count))
            }
        }
    }
}
