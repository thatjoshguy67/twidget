package com.tjg.twidget.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import dev.oneuiproject.oneui.widget.SwitchItemView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetProportionsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private fun renderContext() = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        densityDpi = 160
        fontScale = 1f
        setLocale(Locale.ENGLISH)
    })

    @Test fun renderDesignVariantsAndKeepContainmentScopedToMaterialCards() {
        val context = renderContext()
        val sizes = WidgetPreviews.sizes
        val directory = File(base.cacheDir, "widget-proportions").apply { mkdirs() }
        for (style in WidgetStyle.entries) for (dark in listOf(false, true)) {
            for (contained in listOf(false, true)) {
                val settings = WidgetPreviews.settings(style).copy(logo = TwidgetStore.LOGO_TWITTER,
                    showDelta = true, language = "en", containedFooter = contained)
                val sheet = Bitmap.createBitmap(1120, 412, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet).apply { drawColor(Color.GRAY) }
                for ((row, count) in listOf(7671L, 999_999_999L).withIndex()) {
                    var x = 12
                    sizes.forEach { (w, h) ->
                        val mode = TwidgetWidget.layoutModeForAosp(w, h)
                        val stats = ProfileStats("Test", "thatjoshguy69", count, 0, 0, 0)
                        val bitmap = WidgetArtworkRenderer.render(context, w, h, stats, settings, mode,
                            dark, if (row == 0) 15 else -3, drawBackground = true)
                        canvas.drawBitmap(bitmap, x.toFloat(), (12 + row * 200).toFloat(), null)
                        val opposite = WidgetArtworkRenderer.render(context, w, h, stats,
                            settings.copy(containedFooter = !contained), mode, dark,
                            if (row == 0) 15 else -3, drawBackground = true)
                        val applicable = style == WidgetStyle.MATERIAL && h > 110
                        assertEquals("Containment scope: $style ${w}x$h", !applicable, bitmap.sameAs(opposite))
                        bitmap.recycle()
                        opposite.recycle()
                        x += w + 16
                    }
                }
                File(directory, "${style.name}-$dark-$contained.png").outputStream().use {
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                sheet.recycle()
            }
        }
    }

    @Test fun followerCountGrowsWhenWidgetGetsTaller() {
        val context = renderContext()
        val directory = File(base.cacheDir, "widget-adaptive-count").apply { mkdirs() }
        for (style in WidgetStyle.entries) {
            for (font in listOf(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_SYSTEM)) {
                val settings = WidgetPreviews.settings(style).copy(fontFamily = font,
                    language = "en", containedFooter = true, logo = TwidgetStore.LOGO_TWITTER)
                val stats = ProfileStats("Test", "thatjoshguy69", 7795, 0, 0, 0)
                val sheet = Bitmap.createBitmap(728, 304, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet).apply { drawColor(Color.DKGRAY) }
                val inkHeights = listOf(176, 280).mapIndexed { index, height ->
                    val bitmap = WidgetArtworkRenderer.render(context, 352, height, stats, settings,
                        TwidgetWidget.LAYOUT_MODE_LARGE, true, 7)
                    val rows = (0 until height - 40).filter { y ->
                        (0 until bitmap.width).any { x -> Color.alpha(bitmap.getPixel(x, y)) > 128 }
                    }
                    assertTrue("Count must be visible", rows.isNotEmpty())
                    val inkHeight = rows.last() - rows.first() + 1
                    bitmap.recycle()
                    val artwork = WidgetArtworkRenderer.render(context, 352, height, stats, settings,
                        TwidgetWidget.LAYOUT_MODE_LARGE, true, 7, drawBackground = true)
                    canvas.drawBitmap(artwork, (8 + index * 360).toFloat(), 12f, null)
                    artwork.recycle()
                    inkHeight
                }
                File(directory, "$style-$font.png").outputStream().use {
                    sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                sheet.recycle()
                assertTrue("Taller widget must grow its count: $style / $font / $inkHeights",
                    inkHeights[1] > inkHeights[0] + 35)
            }
        }
    }

    @Test fun followerLinesUseAnEvenBaselineGrid() {
        val context = renderContext()
        val words = "Seven Thousand, Seven Hundred and Ninety Five Followers".split(" ")
        val fonts = listOf(com.tjg.twidget.ui.TwidgetFonts.oneUiSansVariable(context),
            com.tjg.twidget.ui.TwidgetFonts.googleSansFlex(context), Typeface.DEFAULT)
        for (font in fonts) for ((width, height) in listOf(162 to 176, 352 to 176, 352 to 280)) {
            val paints = words.distinct().associateWith { word -> Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(font, if (word == "Followers") 200 else 700, false)
            } }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val baselines = mutableListOf<Float>()
            val canvas = object : Canvas(bitmap) {
                override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
                    baselines += y
                    super.drawText(text, x, y, paint)
                }
            }
            WidgetArtworkRenderer.drawFollowerCount(canvas, words, paints,
                width - 24f, height - 46f, 12f, 6f, 4f)
            val advances = baselines.distinct().zipWithNext { a, b -> b - a }
            assertTrue("Exercise multiple wrapped lines", advances.size >= 2)
            assertTrue("Every baseline must be evenly spaced: $baselines",
                advances.max() - advances.min() < 0.01f)
            bitmap.recycle()
        }
    }

    @Test fun oneUiSansUsesAvailableInkHeightWithoutTouchingFooter() {
        for (density in listOf(160, 480)) {
            val scale = density / 160
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = density
                fontScale = 1f
                setLocale(Locale.ENGLISH)
            })
            for (height in listOf(176, 280)) for (style in WidgetStyle.entries) for (contained in listOf(false, true)) {
                val settings = WidgetPreviews.settings(style).copy(fontFamily = TwidgetStore.FONT_ONE_UI_SANS,
                    language = "en", containedFooter = contained, showDelta = true)
                val bitmap = WidgetArtworkRenderer.render(context, 352 * scale, height * scale,
                    ProfileStats("Test", "thatjoshguy69", 7795, 0, 0, 0), settings,
                    TwidgetWidget.LAYOUT_MODE_LARGE, true, 7)
                val footerTop = (height - 12 - if (style == WidgetStyle.MATERIAL && contained) 20 else 14) * scale
                val ink = android.graphics.Rect()
                for (y in 0 until footerTop) for (x in 0 until bitmap.width) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 128) ink.union(x, y, x + 1, y + 1)
                }
                // The tall case has room for another line; short cards can be
                // limited by a whole-word wrap even at the largest fitting size.
                if (height == 280) assertTrue("One UI Sans still leaves unused height: $style / $contained / $ink",
                    ink.height() >= (footerTop - 20 * scale) * 0.85f)
                assertEquals("Visible letters start at the top padding", 12f * scale, ink.top.toFloat(), 1f)
                assertTrue("Count must keep its footer clearance", ink.bottom <= footerTop - 8 * scale + 1)
                assertTrue("Count stays inside horizontal padding", ink.left >= 12 * scale - 1 &&
                    ink.right <= bitmap.width - 12 * scale + 1)
                bitmap.recycle()
            }
        }
    }

    @Test fun footerLogoStaysTheSameSizeAcrossHandlesAndFonts() {
        val context = renderContext()
        for (font in listOf(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_SYSTEM)) {
            val settings = WidgetPreviews.settings(WidgetStyle.ONE_UI).copy(fontFamily = font,
                logo = TwidgetStore.LOGO_TWITTER, showDelta = false)
            for ((w, h) in listOf(162 to 176, 352 to 76)) {
                var reference: Pair<Int, Int>? = null
                for (handle in listOf("a", "thatjoshguy69", "abcdefghijklmno")) {
                    val bitmap = WidgetArtworkRenderer.render(context, w, h,
                        ProfileStats("Test", handle, 7671, 0, 0, 0), settings,
                        TwidgetWidget.layoutModeForAosp(w, h), false)
                    // The logo is the leftmost ink in the footer and always has a 12dp box.
                    val top = if (h == 76) 46 else 149
                    val bottom = if (h == 76) 64 else 165
                    val pixels = (top until bottom).flatMap { y -> (0 until w).mapNotNull { x ->
                        if (Color.alpha(bitmap.getPixel(x, y)) > 128) x to y else null
                    } }
                    val left = pixels.minOf { it.first }
                    val logoPixels = pixels.filter { it.first < left + 12 }
                    val bounds = (logoPixels.maxOf { it.first } - left + 1) to
                        (logoPixels.maxOf { it.second } - logoPixels.minOf { it.second } + 1)
                    assertTrue("Logo fits 12dp", bounds.first <= 12 && bounds.second <= 12)
                    if (reference == null) reference = bounds else assertEquals("Logo changed with $handle / $font", reference, bounds)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun containedDeltaInkIsCentredInBadge() {
        for (density in listOf(160, 480)) {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = density
                fontScale = 1f
                setLocale(Locale.ENGLISH)
            })
            val scale = density / 160
            for (font in listOf(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_SYSTEM)) {
                for (dark in listOf(false, true)) for (delta in listOf(15L, -3L, 123456789L)) {
                    val settings = WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(fontFamily = font,
                        showDelta = true, language = "en", containedFooter = true)
                    val bitmap = WidgetArtworkRenderer.render(context, 352 * scale, 176 * scale,
                        ProfileStats("Test", "twidget", 7671, 0, 0, 0), settings,
                        TwidgetWidget.LAYOUT_MODE_LARGE, dark, delta)
                    val inkColor = WidgetColors.resolve(context, settings, dark).background
                    val badgeColor = if (delta < 0) Color.rgb(229, 83, 75) else Color.rgb(0, 170, 86)
                    var badgeArea: android.graphics.Rect? = null
                    fun bounds(color: Int): android.graphics.Rect {
                        val result = android.graphics.Rect()
                        for (y in bitmap.height - 80 * scale until bitmap.height) {
                            for (x in bitmap.width / 2 until bitmap.width) {
                                val pixel = bitmap.getPixel(x, y)
                                // Thin glyphs at mdpi may consist entirely of antialiased pixels.
                                val distanceFromBadge = maxOf(kotlin.math.abs(Color.red(pixel) - Color.red(badgeColor)),
                                    kotlin.math.abs(Color.green(pixel) - Color.green(badgeColor)),
                                    kotlin.math.abs(Color.blue(pixel) - Color.blue(badgeColor)))
                                val matches = if (color == badgeColor) pixel == badgeColor
                                    else Color.alpha(pixel) == 255 && distanceFromBadge > 15 &&
                                        badgeArea!!.contains(x, y)
                                if (matches) result.union(x, y, x + 1, y + 1)
                            }
                        }
                        assertFalse("Visible badge/text for $font / $delta", result.isEmpty)
                        return result
                    }
                    val badge = bounds(badgeColor)
                    badgeArea = badge
                    val ink = bounds(inkColor)
                    assertEquals("Horizontal centre: $font / $delta", badge.exactCenterX(), ink.exactCenterX(), 1.5f)
                    assertEquals("Vertical centre: $font / $delta", badge.exactCenterY(), ink.exactCenterY(), 1.5f)
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun containedFooterSavesPerWidgetAndCancelDoesNotSave() {
        val id = 986731
        val prefs = base.getSharedPreferences(TwidgetStore.PREFS, 0)
        val old = prefs.all.filterKeys { it.endsWith("_$id") }
        val settings = TwidgetStore.widgetSettings(base, id).copy(style = WidgetStyle.MATERIAL,
            fontFamily = TwidgetStore.FONT_GOOGLE_SANS_FLEX, containedFooter = false, tintAlpha = 178)
        fun intent() = Intent(base, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        try {
            TwidgetStore.saveWidgetSettings(base, id, settings)
            ActivityScenario.launch<WidgetConfigActivity>(intent()).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.contained_footer_row).visibility)
                    activity.findViewById<SwitchItemView>(R.id.contained_footer_row).isChecked = true
                    assertTrue(activity.findViewById<SwitchItemView>(R.id.contained_footer_row).isChecked)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<SwitchItemView>(R.id.contained_footer_row).isChecked)
                    activity.findViewById<View>(R.id.btn_cancel).performClick()
                }
            }
            assertFalse(TwidgetStore.widgetSettings(base, id).containedFooter)
            ActivityScenario.launch<WidgetConfigActivity>(intent()).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<SwitchItemView>(R.id.contained_footer_row).isChecked = true
                    activity.findViewById<View>(R.id.btn_save).performClick()
                }
            }
            assertTrue(TwidgetStore.widgetSettings(base, id).containedFooter)
            assertEquals(settings.copy(containedFooter = true), TwidgetStore.widgetSettings(base, id))
        } finally {
            prefs.edit().apply {
                prefs.all.keys.filter { it.endsWith("_$id") }.forEach(::remove)
                old.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    else -> error("Unexpected test preference: $key")
                } }
            }.commit()
        }
    }
}
