package com.tjg.twidget.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetArtworkRendererInstrumentedTest {
    @Test fun zeroFollowersFitsTheDefaultSquarePreview() {
        assertArtworkFits(0, "en", TwidgetStore.FONT_ONE_UI_SANS, 176, 1f)
    }

    @Test fun squareArtworkFitsAcrossCountsFontsLanguagesAndFontScales() {
        for (font in listOf(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_SYSTEM)) {
            for (language in listOf("en", "de")) {
                for (size in listOf(120, 176, 224)) {
                    for (scale in listOf(1f, 2f)) {
                        for (count in listOf(1L, 99L, 199L, 1_234L, 999_999_999L)) {
                            assertArtworkFits(count, language, font, size, scale)
                        }
                    }
                }
            }
        }
    }

    private fun assertArtworkFits(count: Long, language: String, font: String, sizeDp: Int, fontScale: Float) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
        })
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt()
        val settings = TwidgetWidgetSettings(
            tintAlpha = 0, tintColor = Color.BLACK, logo = TwidgetStore.LOGO_TWITTER,
            tapAction = TwidgetStore.TAP_REFRESH, accountUsername = "test", colorMode = "dark",
            fontFamily = font, showDelta = false, language = language,
        )
        val bitmap = WidgetArtworkRenderer.render(
            context, size, size, ProfileStats("Test", "test", count, 0, 0, 0), settings,
            TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE, dark = true,
        )
        try {
            if (count == 0L) {
                File(base.cacheDir, "zero-followers.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val pixels = IntArray(size * size)
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
            // Leave two pixels for glyph overhang/antialiasing beyond the 10dp text inset.
            val rightInset = size - (10f * density).toInt() + 2
            val bottomOfText = size - (30f * density).toInt()
            var textPixels = 0
            for (y in 0 until bottomOfText) {
                for (x in 0 until size) {
                    if (Color.alpha(pixels[y * size + x]) == 0) continue
                    textPixels++
                    assertTrue("Text crosses right inset: count=$count language=$language font=$font size=$sizeDp scale=$fontScale at ($x,$y)", x < rightInset)
                }
            }
            assertTrue("Follower text must still be rendered", textPixels > 0)
        } finally {
            bitmap.recycle()
        }
    }
}
