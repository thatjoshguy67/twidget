package com.tjg.twidget.widget.coloros

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.widget.ColorOsBriefViews
import com.tjg.twidget.widget.ColorOsFollowersViews
import com.tjg.twidget.widget.WidgetPreviews
import com.tjg.twidget.widget.WidgetStyle
import org.junit.Assert.*
import org.junit.Test

class ColorOsStandardWidgetsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun refreshDefaultIsPopulatedBeforeTheHostSelectsItsSize() {
        instrumentation.runOnMainSync {
            val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS)
            val sources = listOf(ColorOsFollowersViews.createResponsive(context, 315, 179, settings,
                ProfileStats("Probe", "probe", 7936, 1, 1, 1), 7, null, true),
                ColorOsBriefViews.createResponsive(context, -1, 315, 179, "", null, settings))
            for (source in sources) {
                val parcel = Parcel.obtain()
                source.writeToParcel(parcel, 0); parcel.setDataPosition(0)
                val delivered = android.widget.RemoteViews.CREATOR.createFromParcel(parcel)
                parcel.recycle()
                // No SizeF yet: the launcher's asynchronous/default path must not
                // install an invisible zero-size sentinel on a normal refresh.
                val root = delivered.apply(context, FrameLayout(context))
                assertEquals(View.VISIBLE, root.visibility)
                assertTrue(root.layoutParams.width > 0 && root.layoutParams.height > 0)
                val heroes = listOf(R.id.coloros_native_hero, R.id.coloros_native_hero_night)
                    .map { root.findViewById<ImageView>(it) }
                assertTrue(heroes.any { it.visibility == View.VISIBLE && it.drawable != null })
            }
        }
    }

    @Test fun asynchronousRefreshKeepsContentAtUnchangedHostBounds() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        lateinit var host: android.appwidget.AppWidgetHostView
        val manager = AppWidgetManager.getInstance(context)
        val info = manager.installedProviders.first { it.provider == ColorOsWidgetSupport.followersProvider(context) }
        val density = context.resources.displayMetrics.density
        val w = (260 * density).toInt(); val h = (148 * density).toInt()
        try {
            instrumentation.runOnMainSync {
                host = android.appwidget.AppWidgetHostView(context)
                host.setAppWidget(-1, info); host.setPadding(0, 0, 0, 0)
                host.setExecutor(executor)
            }
            for (count in listOf(7936L, 7940L, 7942L)) {
                instrumentation.runOnMainSync {
                    host.updateAppWidget(ColorOsFollowersViews.createResponsive(context, 315, 179,
                        WidgetPreviews.settings(WidgetStyle.COLOR_OS), ProfileStats("Probe", "probe", count, 1, 1, 1), 7, null, true))
                }
                executor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, w, h) // same rectangle on every refresh
                    val card = host.getChildAt(0)
                    assertEquals(View.VISIBLE, card.visibility)
                    assertTrue(card.width > 110 * density && card.height > 40 * density)
                    val heroes = listOf(R.id.coloros_native_hero, R.id.coloros_native_hero_night)
                        .map { card.findViewById<ImageView>(it) }
                    assertTrue(heroes.any { it.visibility == View.VISIBLE && it.drawable != null })
                    assertTrue(card.right <= w && card.bottom <= h)
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun launcherShortcutTargetsThisVariant() {
        org.junit.Assume.assumeTrue(ColorOsWidgetSupport.available)
        @Suppress("DEPRECATION")
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, ColorOsWidgetBlurProbe.PROVIDER),
            android.content.pm.PackageManager.GET_META_DATA)
        val xml = context.resources.getXml(receiver.metaData.getInt("oplus.appwidget.shortcuts"))
        val intent = try {
            while (!(xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "intent")) xml.next()
            android.content.Intent.parseIntent(context.resources, xml, android.util.Xml.asAttributeSet(xml))
        } finally { xml.close() }
        assertEquals(context.packageName, intent.component!!.packageName)
        assertEquals(ColorOsWidgetSettingsActivity::class.java.name, intent.component!!.className)
        val config = intent.resolveActivityInfo(context.packageManager, 0)!!
        assertTrue(config.exported)
        assertNull(config.permission)
    }

    @Test fun compactFollowersCentreTheWholeRowAndKeepTheDeltaWithEveryFont() {
        org.junit.Assume.assumeTrue(ColorOsWidgetSupport.available)
        instrumentation.runOnMainSync {
            for (font in listOf(com.tjg.twidget.data.TwidgetStore.FONT_SYSTEM,
                com.tjg.twidget.data.TwidgetStore.FONT_GOOGLE_SANS_FLEX,
                com.tjg.twidget.data.TwidgetStore.FONT_ONE_UI_SANS)) {
                for ((w, h) in listOf(110 to 40, 180 to 76, 230 to 76)) {
                    val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS).copy(fontFamily = font, showDelta = true)
                    val root = ColorOsFollowersViews.create(context, w, h, settings,
                        ProfileStats("Probe", "probe", 999999999, 1, 1, 1), 99999, null, true)
                        .apply(context, FrameLayout(context)) as ViewGroup
                    assertEquals("OplusBlurBg", root.findViewById<View>(R.id.coloros_blur_target).tag)
                    assertFalse(root.clipToOutline)
                    val images = listOf(R.id.coloros_native_hero, R.id.coloros_native_hero_night)
                        .map { root.findViewById<ImageView>(it) }
                    val image = images.first { it.visibility == View.VISIBLE }
                    val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                    var left = bitmap.width; var right = -1; var top = bitmap.height; var bottom = -1
                    var deltaPixels = 0
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(x, y)
                        if (android.graphics.Color.alpha(pixel) > 30) {
                            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                            if (android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) + 25) deltaPixels++
                        }
                    }
                    val tolerance = 4 * context.resources.displayMetrics.density
                    assertTrue("centred row font=$font size=$w x $h bounds=$left..$right",
                        kotlin.math.abs((left + right) / 2f - bitmap.width / 2f) < tolerance)
                    assertTrue(kotlin.math.abs((top + bottom) / 2f - bitmap.height / 2f) < tolerance)
                    assertTrue("delta must stay visible at $w x $h with $font", deltaPixels > 0)
                    assertTrue(left > 0 && top >= 0 && right < bitmap.width - 1 && bottom < bitmap.height)
                }
            }
        }
    }

    @Test fun followersConfigurationOpensWithColorOsRemoteViewsPreview() {
        org.junit.Assume.assumeTrue(ColorOsWidgetSupport.available)
        val activity = instrumentation.startActivitySync(android.content.Intent(context,
            com.tjg.twidget.widget.WidgetConfigActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(com.tjg.twidget.widget.WidgetConfigActivity.EXTRA_DEFAULT_KIND, "FOLLOWERS"))
        try {
            instrumentation.runOnMainSync {
                val root = activity.findViewById<ViewGroup>(R.id.widget_config_root)
                fun descendants(view: View): List<View> = listOf(view) +
                    if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                    else emptyList()
                val widgetImages = descendants(root).filter { it.id == R.id.coloros_native_hero }
                assertTrue("Configuration must render the ColorOS preview", widgetImages.isNotEmpty())
                widgetImages.forEach { assertEquals(ImageView::class.java, it.javaClass) }
                val pager = activity.findViewById<androidx.viewpager.widget.ViewPager>(R.id.widget_style_pager)
                pager.setCurrentItem(WidgetStyle.entries.indexOf(WidgetStyle.COLOR_OS), false)
                assertEquals(View.GONE, root.findViewById<View>(R.id.opacity_block).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.tint_row).visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.font_row).visibility)
                pager.setCurrentItem(WidgetStyle.entries.indexOf(WidgetStyle.ONE_UI), false)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.opacity_block).visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.tint_row).visibility)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun defaultManifestRetainsNormalEntriesAndHidesTheColorOsAdapter() {
        val packageManager = context.packageManager
        fun features(component: ComponentName): Int {
            @Suppress("DEPRECATION")
            val receiver = packageManager.getReceiverInfo(component, android.content.pm.PackageManager.GET_META_DATA)
            val xml = context.resources.getXml(receiver.metaData.getInt("android.appwidget.provider"))
            try {
                while (xml.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) xml.next()
                return xml.getAttributeIntValue("http://schemas.android.com/apk/res/android", "widgetFeatures", 0)
            } finally { xml.close() }
        }
        val hidden = AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER
        assertEquals(0, features(ComponentName(context, com.tjg.twidget.TwidgetWidget::class.java)) and hidden)
        assertEquals(0, features(ComponentName(context, com.tjg.twidget.widget.TwidgetBriefWidget::class.java)) and hidden)
        assertNotEquals(0, features(ComponentName(context, ColorOsWidgetBlurProbe.PROVIDER)) and hidden)
    }

    @Test fun normalPickerEntriesPreserveBindingsAndExposeNativeFollowersOnlyOnColorOs() {
        org.junit.Assume.assumeTrue(ColorOsWidgetSupport.available)
        val manager = AppWidgetManager.getInstance(context)
        val native = ComponentName(context, ColorOsWidgetBlurProbe.PROVIDER)
        assertEquals(native, ColorOsWidgetSupport.followersProvider(context))
        val legacy = ComponentName(context, com.tjg.twidget.TwidgetWidget::class.java)
        val brief = ComponentName(context, com.tjg.twidget.widget.TwidgetBriefWidget::class.java)
        val before = listOf(native, legacy, brief).associateWith { manager.getAppWidgetIds(it).toList() }
        ColorOsWidgetSupport.reconcilePicker(context)
        ColorOsWidgetSupport.reconcilePicker(context)
        val providers = manager.installedProviders.associateBy { it.provider }
        fun hidden(component: ComponentName) = providers.getValue(component).widgetFeatures and
            AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER != 0
        assertFalse(hidden(native))
        assertTrue(hidden(legacy))
        assertFalse(hidden(brief))
        assertEquals(context.getString(R.string.widget_label), providers.getValue(native).loadLabel(context.packageManager))
        before.forEach { (component, ids) -> assertEquals(ids, manager.getAppWidgetIds(component).toList()) }
    }

    @Test fun onlyColorOsFollowersRequestNativeBlurAndFallbacksHaveVisibleSurfaces() {
        instrumentation.runOnMainSync {
            val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS)
            val stats = ProfileStats("Probe", "probe", 7936, 1, 1, 1)
            val native = ColorOsFollowersViews.create(context, 348, 157, settings, stats, -1, null, true)
                .apply(context, FrameLayout(context))
            assertEquals("OplusBlurBg", native.findViewById<View>(R.id.coloros_blur_target).tag)
            val fallback = ColorOsFollowersViews.create(context, 348, 157, settings, stats, -1, null, false)
                .apply(context, FrameLayout(context))
            assertNull(fallback.findViewById<View>(R.id.coloros_blur_target).tag)
            assertNotNull(fallback.findViewById<View>(R.id.coloros_blur_target).background)
            val customFont = ColorOsFollowersViews.create(context, 348, 157,
                settings.copy(fontFamily = com.tjg.twidget.data.TwidgetStore.FONT_GOOGLE_SANS_FLEX), stats, -1, null, false)
                .apply(context, FrameLayout(context))
            assertEquals(View.VISIBLE, customFont.findViewById<View>(R.id.coloros_blur_target).visibility)
            assertNotNull(customFont.findViewById<ImageView>(R.id.coloros_native_hero).drawable)
            val brief = ColorOsBriefViews.create(context, -1, 348, 157, "", null, settings)
                .apply(context, FrameLayout(context))
            assertNull(brief.findViewById<View>(R.id.coloros_blur_target).tag)
            assertEquals(View.VISIBLE, brief.findViewById<View>(R.id.coloros_blur_target).visibility)
            assertNotNull(brief.findViewById<View>(R.id.coloros_blur_target).backgroundTintList)
            assertTrue(android.graphics.Color.alpha(brief.findViewById<View>(R.id.coloros_blur_target).backgroundTintList!!.defaultColor) > 0)
            assertNotNull(brief.findViewById<ImageView>(R.id.coloros_native_hero).drawable)
            assertTrue(brief.contentDescription.isNotBlank())
            // Other styles on the ColorOS-only adapter use their original artwork,
            // with no ColorOS blur tag or additional fallback painted behind it.
            for (style in listOf(WidgetStyle.ONE_UI, WidgetStyle.MATERIAL)) {
                val other = ColorOsFollowersViews.create(context, 348, 157,
                    settings.copy(style = style, fontFamily = style.defaultFont), stats, 0, null, false)
                    .apply(context, FrameLayout(context))
                assertNull(other.findViewById<View>(R.id.coloros_blur_target).tag)
                assertEquals(View.GONE, other.findViewById<View>(R.id.coloros_blur_target).visibility)
            }
        }
    }

    @Test fun serializedBriefAndLegacyFollowersFitTheirFallbackResizeVariants() {
        val settings = WidgetPreviews.settings(WidgetStyle.COLOR_OS)
        val stats = ProfileStats("Probe", "probe", 7936, 1, 1, 1)
        val sources = listOf(
            ColorOsBriefViews.createResponsive(context, -1, 348, 254, "", null, settings),
            ColorOsFollowersViews.createResponsive(context, 348, 254, settings, stats, 1, null, false))
        instrumentation.runOnMainSync {
            for (source in sources) {
                val p = Parcel.obtain()
                source.writeToParcel(p, 0)
                p.setDataPosition(0)
                val delivered = android.widget.RemoteViews.CREATOR.createFromParcel(p)
                p.recycle()
                val info = AppWidgetManager.getInstance(context).installedProviders.first { it.provider.packageName == context.packageName }
                val host = android.appwidget.AppWidgetHostView(context)
                host.setAppWidget(-1, info)
                host.setPadding(0, 0, 0, 0)
                host.clipChildren = false
                host.clipToPadding = false
                host.updateAppWidget(delivered)
                for ((w, h) in listOf(260 to 148, 148 to 148, 348 to 76, 110 to 40, 348 to 254)) {
                    val density = context.resources.displayMetrics.density
                    val width = (w * density).toInt()
                    val height = (h * density).toInt()
                    host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    host.layout(0, 0, width, height)
                    val card = host.getChildAt(0)
                    assertTrue("$w x $h", card.left >= 0 && card.top >= 0 && card.right <= width && card.bottom <= height)
                    assertNull(card.findViewById<View>(R.id.coloros_blur_target)?.tag)
                }
            }
        }
    }
}
