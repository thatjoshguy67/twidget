package com.tjg.twidget.widget.coloros

import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.os.Parcel
import android.widget.RemoteViews
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import android.graphics.RectF
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
import kotlin.math.roundToInt

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
            assertFalse("Let the launcher outline its own blur and contour-glow surface", root.clipToOutline)
            val outline = Outline()
            root.background.getOutline(outline)
            assertTrue(outline.canClip())
            assertNull(root.findViewById<View>(R.id.widget_artwork))
            assertNotNull(root.findViewById<ImageView>(R.id.coloros_native_hero).drawable)
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
                for (followers in listOf(7934L, Long.MAX_VALUE)) for ((w, h) in sizes) {
                    val views = ColorOsNativeFollowerViews.create(themed, w, h, settings,
                        ProfileStats("Probe", "averylongaccountname", followers, 1, 1, 1), 999999999, tap)
                    val parcel = Parcel.obtain()
                    views.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    val delivered = RemoteViews.CREATOR.createFromParcel(parcel)
                    parcel.recycle()
                    val root = delivered.apply(themed, FrameLayout(themed)) as ViewGroup
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
                        assertCountImageFits(root)
                    }
                    assertEquals(View.GONE, root.findViewById<View>(R.id.widget_loading).visibility)
                }
            }
        }
    }

    @Test fun deliveredParagraphFitsInLauncherTheme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val launcher = context.createPackageContext("com.android.launcher", 0)
            val host = android.view.ContextThemeWrapper(launcher, launcher.applicationInfo.theme)
            val views = ColorOsNativeFollowerViews.create(context, 330, 157,
                WidgetPreviews.settings(WidgetStyle.COLOR_OS), ProfileStats("Probe", "probe", 7934, 1, 1, 1), 7, null)
            val parcel = Parcel.obtain()
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val delivered = RemoteViews.CREATOR.createFromParcel(parcel)
            parcel.recycle()
            val root = delivered.apply(host, FrameLayout(host))
            val density = host.resources.displayMetrics.density
            val w = (330 * density).toInt()
            val h = (157 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
            assertCountImageFits(root)
        }
    }

    private fun assertCountImageFits(root: View) {
        val hero = root.findViewById<ImageView>(R.id.coloros_native_hero)
        val bounds = RectF(hero.drawable.bounds)
        hero.imageMatrix.mapRect(bounds)
        assertTrue("paragraph=$bounds viewport=${hero.width}x${hero.height}",
            bounds.left >= -1 && bounds.top >= -1 && bounds.right <= hero.width + 1 && bounds.bottom <= hero.height + 1)
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
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

    @Test fun paragraphFitsWhenActualHostSizeChangesWithoutNewRemoteViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val root = ColorOsNativeFollowerViews.create(context, 348, 157,
                WidgetPreviews.settings(WidgetStyle.COLOR_OS), ProfileStats("Probe", "probe", 7934, 1, 1, 1), 7, null)
                .apply(context, FrameLayout(context))
            for ((width, height) in listOf(348 to 157, 162 to 176, 120 to 121, 348 to 104, 176 to 352)) {
                val density = context.resources.displayMetrics.density
                val w = (width * density).toInt()
                val h = (height * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
                assertCountImageFits(root)
            }
        }
    }

    @Test fun countArtworkHasTransparentBackgroundAndThemeAwareInk() {
        for (dark in listOf(false, true)) {
            val bitmap = ColorOsSystemHero.render(context, WidgetPreviews.settings(WidgetStyle.COLOR_OS),
                listOf("Seven", "Thousand,", "Nine", "Hundred", "and", "Thirty", "Four", "Followers", "+7"),
                7, 7, 348, 157, dark)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue(pixels.any { Color.alpha(it) == 0 })
            val primary = if (dark) Color.WHITE else 0xFF18202A.toInt()
            assertTrue("Count ink must follow night mode", pixels.any { it == primary })
            bitmap.recycle()
        }
    }

    @Test fun explicitProbeBoundsSurviveInstalledColorOsMeasurementAndReapply() {
        // Exercise the OEM's actual rule, not an imitation of its arithmetic.
        val launcher = runCatching { context.createPackageContext("com.android.launcher",
            android.content.Context.CONTEXT_INCLUDE_CODE or android.content.Context.CONTEXT_IGNORE_SECURITY) }.getOrNull()
        org.junit.Assume.assumeNotNull(launcher)
        val ruleClass = launcher!!.classLoader.loadClass("com.android.launcher3.widget.measure.AllMatchParentMeasureAlignSpecRule")
        // R8 can remove this stateless rule's constructor while retaining its method.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val rule = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), ruleClass)
        val measure = ruleClass.getMethod("measure", View::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, android.graphics.Point::class.java)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(context)
            val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS)
            val stats = ProfileStats("Probe", "probe", 7936, 1, 1, 1)
            val density = context.resources.displayMetrics.density
            var root: View? = null
            // Include compact/tall transitions, the 3x2 case, and a larger tall card.
            for ((w, h) in listOf(260 to 148, 348 to 254, 110 to 76, 348 to 157)) {
                val remote = ColorOsNativeFollowerViews.create(context, w, h, settings, stats, -1, null)
                val parcel = Parcel.obtain()
                remote.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val delivered = RemoteViews.CREATOR.createFromParcel(parcel)
                parcel.recycle()
                val current = root
                val next = if (current != null && current.tag == ColorOsNativeFollowerViews.layout(h)) {
                    delivered.reapply(context, current)
                    current
                } else delivered.apply(context, parent)
                next.tag = ColorOsNativeFollowerViews.layout(h)
                root = next
                val width = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, w.toFloat(), context.resources.displayMetrics).roundToInt()
                val height = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, h.toFloat(), context.resources.displayMetrics).roundToInt()
                // A larger canonical measurement must not enlarge the bounded root.
                measure.invoke(rule, next, width, height, 1.5f, android.graphics.Point(3, 2))
                next.layout(0, 0, next.measuredWidth, next.measuredHeight)
                assertEquals("width at $w x $h", width, next.measuredWidth)
                assertEquals("height at $w x $h", height, next.measuredHeight)
                assertEquals(width, next.findViewById<View>(R.id.coloros_blur_target).width)
                assertEquals(height, next.findViewById<View>(R.id.coloros_blur_target).height)
                if (h > 110) assertCountImageFits(next)
            }
            // Control: the unchanged match-parent arrangement reproduces the bug.
            val control = FrameLayout(context).apply {
                id = android.R.id.background
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            measure.invoke(rule, control, (260 * density).toInt(), (148 * density).toInt(), 1.5f, android.graphics.Point(3, 2))
            assertTrue(control.measuredWidth > (260 * density).toInt())
            assertTrue(control.measuredHeight > (148 * density).toInt())
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
                assertEquals("Ordinary provider root width for $style", ViewGroup.LayoutParams.MATCH_PARENT, root.layoutParams.width)
                assertEquals("Ordinary provider root height for $style", ViewGroup.LayoutParams.MATCH_PARENT, root.layoutParams.height)
            }
        }
    }
}
