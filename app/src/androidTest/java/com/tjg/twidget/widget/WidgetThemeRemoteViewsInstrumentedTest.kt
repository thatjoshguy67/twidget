package com.tjg.twidget.widget

import android.content.res.Configuration
import android.graphics.drawable.BitmapDrawable
import android.os.Parcel
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetThemeRemoteViewsInstrumentedTest {
    @Test fun launcherSelectsThemeWithoutAnotherProviderUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = 97539
        val original = TwidgetStore.widgetSettings(context, id)
        try {
            for (style in WidgetStyle.entries) {
                for (mode in listOf(TwidgetStore.COLOR_MODE_SYSTEM, TwidgetStore.COLOR_MODE_LIGHT, TwidgetStore.COLOR_MODE_DARK)) {
                    val settings = original.copy(style = style, colorMode = mode, fontFamily = style.defaultFont)
                    TwidgetStore.saveWidgetSettings(context, id, settings)
                    for (brief in listOf(false, true)) {
                        // Build and parcel exactly once, as when sent to a launcher.
                        val source = if (brief) TwidgetBriefWidget.createViews(context, id, 352, 176, "", null)
                        else TwidgetWidget.createRemoteViews(context, id, 352, 176, TwidgetWidget.LAYOUT_MODE_LARGE,
                            settings, "test", ProfileStats("Test", "test", 7782, 0, 0, 0), 0, false)
                        val parcel = Parcel.obtain()
                        val cached = try {
                            source.writeToParcel(parcel, 0)
                            parcel.setDataPosition(0)
                            RemoteViews.CREATOR.createFromParcel(parcel)
                        } finally { parcel.recycle() }
                        for (hostDark in listOf(false, true, false)) {
                            val expectedDark = if (mode == TwidgetStore.COLOR_MODE_SYSTEM) hostDark else mode == TwidgetStore.COLOR_MODE_DARK
                            val configuration = Configuration(context.resources.configuration).apply {
                                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                    if (hostDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                            }
                            val hostContext = context.createConfigurationContext(configuration)
                            instrumentation.runOnMainSync {
                                val root = cached.apply(hostContext, FrameLayout(hostContext))
                                val expected = WidgetColors.resolve(context, settings, expectedDark)
                                assertEquals("$style/$mode background must match launcher theme $hostDark",
                                    expected.background, root.findViewById<View>(android.R.id.background).backgroundTintList!!.defaultColor)
                                val artwork = root.findViewById<ImageView>(if (brief) R.id.brief_widget_artwork else R.id.widget_artwork)
                                val bitmap = (artwork.drawable as BitmapDrawable).bitmap
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                                assertTrue("$style/$mode artwork must match launcher theme $hostDark", pixels.contains(expected.primary))
                            }
                        }
                    }
                }
            }
        } finally { TwidgetStore.saveWidgetSettings(context, id, original) }
    }

    @Test fun launcherResizeSequenceUsesExactArtworkDimensions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val host = android.appwidget.AppWidgetHost(context, 97540)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            for (brief in listOf(false, true)) {
                val id = host.allocateAppWidgetId()
                val provider = android.content.ComponentName(context,
                    if (brief) TwidgetBriefWidget::class.java else com.tjg.twidget.TwidgetWidget::class.java)
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, provider))
                TwidgetStore.saveWidgetSettings(context, id, TwidgetStore.widgetSettings(context, id).copy(
                    style = WidgetStyle.MATERIAL, fontFamily = TwidgetStore.FONT_GOOGLE_SANS_FLEX))
                for ((width, height) in listOf(180 to 176, 180 to 280, 352 to 280, 352 to 176, 180 to 76)) {
                    val options = android.os.Bundle().apply {
                        putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                        putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                        putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                        putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                        putParcelableArrayList(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES,
                            arrayListOf(android.util.SizeF(width.toFloat(), height.toFloat())))
                    }
                    manager.updateAppWidgetOptions(id, options)
                    if (brief) TwidgetBriefWidget.updateWidget(context, manager, id)
                    else TwidgetWidget.updateWidget(context, manager, id)
                    instrumentation.runOnMainSync {
                        val view = host.createView(context, id, manager.getAppWidgetInfo(id))
                        val artwork = view.findViewById<ImageView>(if (brief) R.id.brief_widget_artwork else R.id.widget_artwork)
                        assertNotNull("Launcher must receive rendered artwork", artwork)
                        val bitmap = (artwork.drawable as BitmapDrawable).bitmap
                        val density = context.resources.displayMetrics.density
                        assertEquals("Resized artwork width", (width * density).toInt(), bitmap.width)
                        assertEquals("Resized artwork height", (height * density).toInt(), bitmap.height)
                    }
                }
            }
        } finally {
            host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun briefStripWrapsLongHeadlineBesideIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val headline = "Momentum is building"
        val snapshot = com.tjg.twidget.brief.BriefSnapshot("test", 0, 0, 0, 0, 7782, 0, 0, 0, 0,
            listOf(com.tjg.twidget.brief.BriefCard("fixture", com.tjg.twidget.brief.BriefCardType.STREAK, headline, "Keep it up", 0)),
            headline = headline, subheading = "Keep it up", shortDescription = "Keep it up",
            language = com.tjg.twidget.brief.BriefStrings.from(context).languageTag, topFollowerRanks = emptyMap())
        for (font in listOf(TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_SYSTEM)) {
            val density = context.resources.displayMetrics.density
            val widthDp = if (font == TwidgetStore.FONT_GOOGLE_SANS_FLEX) 352 else 280
            val bitmap = BriefWidgetArtworkRenderer.render(context, (widthDp * density).toInt(), (76 * density).toInt(),
                "test", snapshot, true, font, style = WidgetStyle.MATERIAL)
            val primary = WidgetColors.resolve(context, WidgetStyle.MATERIAL, true).primary
            val textLeft = (76 * density).toInt()
            val rows = (0 until bitmap.height).map { y -> (textLeft until bitmap.width).any { x -> bitmap.getPixel(x, y) == primary } }
            val bands = rows.indices.count { rows[it] && (it == 0 || !rows[it - 1]) }
            assertEquals("Long headline should occupy two lines with $font", 2, bands)
            assertFalse(rows.first())
            assertFalse(rows.last())
            if (font == TwidgetStore.FONT_GOOGLE_SANS_FLEX) {
                java.io.File(context.cacheDir, "brief-wrapped.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            bitmap.recycle()
        }
    }

    @Test fun materialDarkSurfaceUsesTheLighterWallpaperTone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val colors = WidgetColors.resolve(context, WidgetStyle.MATERIAL, true)
        assertEquals(context.getColor(android.R.color.system_accent2_800), colors.background)
        assertTrue(androidx.core.graphics.ColorUtils.calculateLuminance(colors.background) >
            androidx.core.graphics.ColorUtils.calculateLuminance(context.getColor(android.R.color.system_accent2_900)))
    }
}
