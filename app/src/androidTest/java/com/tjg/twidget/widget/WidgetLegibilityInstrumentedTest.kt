package com.tjg.twidget.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetLegibilityInstrumentedTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val context get() = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        densityDpi = 160
        fontScale = 1f
    })

    @Test fun signedChangesContrastWithDarkLightAndWallpaperTints() {
        for (background in listOf(Color.BLACK, Color.WHITE, 0xFF126775.toInt(),
            0xFF1D352C.toInt(), 0xFFDCF7E9.toInt(), Color.GRAY)) {
            val dark = ColorUtils.calculateLuminance(background) < 0.5
            val colors = WidgetColors(background, Color.WHITE, Color.WHITE)
            for (delta in listOf(-1L, 1L)) {
                val color = colors.deltaColor(delta, dark)
                assertTrue("Delta contrast against ${Integer.toHexString(background)}",
                    ColorUtils.calculateContrast(color, background) >= 4.5)
                assertTrue("Positive change remains green", delta < 0 || Color.green(color) > Color.red(color))
                assertTrue("Negative change remains red", delta > 0 || Color.red(color) > Color.green(color))
            }
        }
        for (style in WidgetStyle.entries) for (dark in listOf(false, true)) {
            val colors = WidgetColors.resolve(context, WidgetPreviews.settings(style), dark)
            for (delta in listOf(-1L, 1L)) assertTrue(
                ColorUtils.calculateContrast(colors.deltaColor(delta, dark),
                    ColorUtils.setAlphaComponent(colors.background, 255)) >= 4.5)
        }
    }

    @Test fun compactLogosStayProportionalWhenCountsAndWidgetsResize() {
        val sheet = Bitmap.createBitmap(520, 684, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet).apply { drawColor(0xFF126775.toInt()) }
        try {
            for ((fontIndex, font) in listOf(TwidgetStore.FONT_ONE_UI_SANS,
                TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_SYSTEM).withIndex()) {
                for ((row, count) in listOf(4683L, 999_999_999L).withIndex()) {
                    var left = 8f
                    for ((width, height) in listOf(100 to 56, 162 to 76, 222 to 100)) {
                        val settings = WidgetPreviews.settings(WidgetStyle.ONE_UI).copy(
                            fontFamily = font, logo = TwidgetStore.LOGO_TWITTER, showDelta = true)
                        val stats = ProfileStats("Test", "test", count, 0, 0, 0)
                        val bitmap = WidgetArtworkRenderer.render(context, width, height, stats, settings,
                            TwidgetWidget.LAYOUT_MODE_COMPACT_2X1, true, 1)
                        try {
                            val columns = (0 until width).filter { x ->
                                (0 until height).any { y -> Color.alpha(bitmap.getPixel(x, y)) > 128 }
                            }
                            assertTrue("Logo and count are visible", columns.isNotEmpty())
                            val iconEnd = generateSequence(columns.first()) { it + 1 }
                                .first { it >= width || it !in columns }
                            val icon = Rect()
                            val countInk = Rect()
                            for (y in 0 until height) for (x in columns) {
                                val pixel = bitmap.getPixel(x, y)
                                if (Color.alpha(pixel) <= 128) continue
                                if (x < iconEnd) icon.union(x, y, x + 1, y + 1)
                                else if (Color.red(pixel) == 255 &&
                                    Color.green(pixel) == 255 && Color.blue(pixel) == 255)
                                    countInk.union(x, y, x + 1, y + 1)
                            }
                            assertTrue("Count is visible: $font / $count / $width", !countInk.isEmpty)
                            assertTrue("Logo must not dominate: $icon vs $countInk / $font / $count / $width",
                                icon.height() <= countInk.height() + 2)
                            assertTrue("Row stays inside padding", columns.first() >= 8 && columns.last() < width - 8)
                        } finally { bitmap.recycle() }
                        val artwork = WidgetArtworkRenderer.render(context, width, height, stats, settings,
                            TwidgetWidget.LAYOUT_MODE_COMPACT_2X1, true, 1, drawBackground = true)
                        canvas.drawBitmap(artwork, left, (8 + fontIndex * 228 + row * 112).toFloat(), null)
                        artwork.recycle()
                        left += width + 12
                    }
                }
            }
            File(base.cacheDir, "widget-legibility.png").outputStream().use {
                sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { sheet.recycle() }
    }
}
