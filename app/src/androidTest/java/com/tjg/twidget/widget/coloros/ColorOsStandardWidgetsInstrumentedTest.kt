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
            assertEquals(View.GONE, customFont.findViewById<View>(R.id.coloros_blur_target).visibility)
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
