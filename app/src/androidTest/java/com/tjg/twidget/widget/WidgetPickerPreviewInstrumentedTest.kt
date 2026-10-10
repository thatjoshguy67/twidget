package com.tjg.twidget.widget

import android.content.res.Configuration
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetPickerPreviewInstrumentedTest {
    @Test fun xmlPreviewsMatchLiveArtworkInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val sheet = Bitmap.createBitmap(1120, 1800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet).apply { drawColor(Color.DKGRAY) }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f }
        var row = 0
        for (style in WidgetStyle.entries) for (dark in listOf(false, true)) for (brief in listOf(false, true)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 480
                fontScale = 1f
                setLocale(Locale.ENGLISH)
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            val settings = WidgetPreviews.settings(style).copy(language = "en")
            val colors = WidgetColors.resolve(context, settings, dark)
            assertEquals(colors.primary, context.getColor(if (style == WidgetStyle.ONE_UI) R.color.picker_one_ui_primary else R.color.picker_material_primary))
            assertEquals(colors.background or Color.BLACK, context.getColor(if (style == WidgetStyle.ONE_UI) R.color.picker_one_ui_background else R.color.picker_material_background))
            val names = if (style == WidgetStyle.ONE_UI) {
                if (brief) listOf("widget_preview_brief_compact", "widget_preview_brief_strip", "widget_preview_brief_square", "widget_preview_brief")
                else listOf("widget_preview_strip", "widget_preview_row", "widget_preview_square", "widget_preview_large")
            } else listOf("strip", "row", "square", "large").map { "widget_preview_material_${if (brief) "brief" else "followers"}_$it" }
            val top = row++ * 220
            canvas.drawText("${style.name} / ${if (dark) "dark" else "light"} / ${if (brief) "Brief" else "Followers"}", 12f, top + 18f, label)
            var left = 12
            for ((index, size) in WidgetPreviews.sizes.withIndex()) {
                val (w, h) = size
                @Suppress("DiscouragedApi")
                val id = context.resources.getIdentifier(names[index], "layout", context.packageName)
                assertNotEquals(0, id)
                val actual = Bitmap.createBitmap(w * 3, h * 3, Bitmap.Config.ARGB_8888)
                instrumentation.runOnMainSync {
                    val view = RemoteViews(context.packageName, id).apply(context, FrameLayout(context))
                    view.measure(View.MeasureSpec.makeMeasureSpec(actual.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(actual.height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, actual.width, actual.height)
                    view.draw(Canvas(actual))
                }
                val expected = WidgetPreviews.artwork(context, settings, brief, w, h, dark)
                val a = IntArray(actual.width * actual.height)
                val e = IntArray(a.size)
                actual.getPixels(a, 0, actual.width, 0, 0, actual.width, actual.height)
                expected.getPixels(e, 0, expected.width, 0, 0, expected.width, expected.height)
                var bad = 0
                for (i in a.indices) {
                    if (maxOf(kotlin.math.abs(Color.red(a[i]) - Color.red(e[i])),
                        kotlin.math.abs(Color.green(a[i]) - Color.green(e[i])),
                        kotlin.math.abs(Color.blue(a[i]) - Color.blue(e[i])),
                        kotlin.math.abs(Color.alpha(a[i]) - Color.alpha(e[i]))) > 18) bad++
                }
                val mismatch = bad.toDouble() / a.size
                if (mismatch >= .015) {
                    File(base.cacheDir, "picker-failed-actual.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(base.cacheDir, "picker-failed-expected.png").outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                assertTrue("${names[index]} dark=$dark differs from live artwork by $mismatch", mismatch < .015)
                canvas.drawBitmap(actual, null, Rect(left, top + 30, left + w, top + 30 + h), null)
                left += w + 16
                actual.recycle()
                expected.recycle()
            }
        }
        File(base.cacheDir, "picker-preview-sheet.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
    }

    @Test fun pickerSettingsAndMetadataUseNativeStyleAndOnlyFourSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (style in WidgetStyle.entries) {
            val settings = WidgetPreviews.settings(style)
            assertEquals(style.defaultFont, settings.fontFamily)
            assertEquals(TwidgetStore.COLOR_MODE_SYSTEM, settings.colorMode)
            assertFalse(settings.showDelta)
        }
        for (xml in listOf(R.xml.widget_provider_blur, R.xml.widget_provider_brief)) {
            context.resources.getXml(xml).use { parser ->
                while (parser.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
                val attributes = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                assertEquals("0x1e", attributes["widgetSize"])
                assertEquals("0x1e", attributes["featuredWidget"])
                assertFalse(attributes.containsKey("previewLayoutExtraLarge"))
                assertFalse(attributes.containsKey("previewLayoutExtraLargeLong"))
            }
        }
    }

    @Test fun exportLargeDeltaExample() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        val settings = WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(showDelta = true, language = "en")
        val bitmap = WidgetArtworkRenderer.render(context, (352 * density).toInt(), (176 * density).toInt(),
            com.tjg.twidget.data.ProfileStats("Twidget", "twidget", 7783, 0, 0, 0), settings,
            TwidgetWidget.LAYOUT_MODE_LARGE, false, delta = 21, drawBackground = true)
        File(context.cacheDir, "picker-delta-example.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
