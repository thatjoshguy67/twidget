package com.tjg.twidget.widget.coloros

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.text.TextPaint
import android.text.Spanned
import android.text.style.StyleSpan
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
import com.tjg.twidget.widget.ColorOsSystemHero
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
            val hero = root.findViewById<ViewGroup>(R.id.coloros_native_hero)
            assertTrue(hero.childCount > 4)
            assertTrue((0 until hero.childCount).all { hero.getChildAt(it) is TextView })
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
                        val child = root.findViewById<View>(id) ?: continue
                        if (child.visibility == View.GONE) continue
                        val rect = Rect(0, 0, child.width, child.height)
                        root.offsetDescendantRectToMyCoords(child, rect)
                        assertTrue("$w x $h font=$fontScale rect=$rect", rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height)
                    }
                    val count = root.findViewById<TextView>(R.id.coloros_native_count)
                    if (count != null) {
                        assertTrue(count.text.isNotEmpty())
                        assertEquals("complete count at $w x $h scale=$fontScale", 0, count.layout.getEllipsisCount(0))
                        assertTrue("count line at $w x $h scale=$fontScale", count.layout.getLineWidth(0) <= count.width - count.paddingLeft - count.paddingRight + 1)
                        assertTrue("text height=${count.layout.height} box=${count.height}", count.layout.height <= count.height + 1)
                        assertEquals(if (dark) Color.WHITE else 0xFF18202A.toInt(), count.currentTextColor)
                    } else {
                        val hero = root.findViewById<ViewGroup>(R.id.coloros_native_hero)
                        assertTrue(hero.childCount > 0)
                        for (i in 0 until hero.childCount) {
                            val word = hero.getChildAt(i) as TextView
                            val paint = TextPaint(word.paint)
                            val text = word.text as Spanned
                            text.getSpans(0, text.length, StyleSpan::class.java).forEach { it.updateMeasureState(paint) }
                            assertTrue("native line $text: width=${word.layout.getLineWidth(0)} available=${word.width - word.paddingLeft - word.paddingRight}",
                                word.layout.getLineWidth(0) <= word.width - word.paddingLeft - word.paddingRight + 1)
                            val ink = Rect()
                            paint.getTextBounds(text.toString(), 0, text.length, ink)
                            ink.offset(word.left + word.paddingLeft, word.top + word.baseline)
                            assertTrue("word=$text ink=$ink hero=${hero.width}x${hero.height}",
                                ink.left >= -2 && ink.top >= -2 && ink.right <= hero.width + 2 && ink.bottom <= hero.height + 2)
                        }
                    }
                    assertEquals(View.GONE, root.findViewById<View>(R.id.widget_loading).visibility)
                }
            }
        }
    }

    @Test fun compactCountShrinksInsteadOfEllipsizingWhenSpaceDecreases() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sizes = listOf(352, 110).map { width ->
                val root = ColorOsNativeFollowerViews.create(context, width, 76,
                    WidgetPreviews.settings(WidgetStyle.COLOR_OS).copy(showDelta = false),
                    ProfileStats("Probe", "probe", Long.MAX_VALUE, 1, 1, 1), 0, null)
                    .apply(context, FrameLayout(context))
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt()
                val h = (76 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                val count = root.findViewById<TextView>(R.id.coloros_native_count)
                assertEquals(0, count.layout.getEllipsisCount(0))
                count.textSize
            }
            assertTrue("The narrower card must shrink its count", sizes[1] < sizes[0])
        }
    }

    @Test fun nativeWordsRetainTheSharedSystemFontFitterSizeAndEmphasis() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS).copy(showDelta = true)
            val stats = ProfileStats("Probe", "probe", 7935, 1, 1, 1)
            val tap = PendingIntent.getActivity(context, 778, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val root = ColorOsNativeFollowerViews.create(context, 330, 157, settings, stats, 8, tap)
                .apply(context, FrameLayout(context))
            val tokens = TwidgetWidget.followersInWords(7935).split(" ")
            val expected = ColorOsSystemHero.fit(context, settings, tokens + "Followers" + "+8", tokens.size, 330, 157)
                .lines.flatten()
            val hero = root.findViewById<ViewGroup>(R.id.coloros_native_hero)
            assertEquals(expected.size, hero.childCount)
            expected.forEachIndexed { i, run ->
                val word = hero.getChildAt(i) as TextView
                assertEquals(run.text, word.text.toString())
                assertEquals(run.paint.textSize, word.textSize, 0.01f)
                val paint = TextPaint(word.paint)
                val text = word.text as Spanned
                text.getSpans(0, text.length, StyleSpan::class.java).forEach { it.updateMeasureState(paint) }
                assertEquals(run.paint.typeface.weight, paint.typeface.weight)
                assertEquals(run.paint.isFakeBoldText, paint.isFakeBoldText)
                assertEquals(run.advance, paint.measureText(word.text.toString()), 1f)
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
