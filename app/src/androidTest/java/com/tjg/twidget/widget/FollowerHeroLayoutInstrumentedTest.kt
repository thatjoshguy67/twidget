package com.tjg.twidget.widget

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowerHeroLayoutInstrumentedTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        densityDpi = 160
        fontScale = 1f
        setLocale(Locale.ENGLISH)
    })
    private val fonts = listOf(TwidgetStore.FONT_GOOGLE_SANS_FLEX, TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_SYSTEM)

    @Test fun fittedRunsPreserveWholeWordsAndStayInsideTheHero() {
        for (font in fonts) for (language in listOf("en", "de"))
            for (count in listOf(0L, 116L, 7671L, 999_999_999L, Long.MAX_VALUE)) {
            val words = TwidgetWidget.followersInWords(count, AppLocales.resolve(language)).split(Regex("\\s+"))
            val allWords = words + "Followers" + "+123,456"
            val hero = WidgetArtworkRenderer.followerHero(context,
                WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(fontFamily = font),
                allWords, words.size, Color.BLACK, Color.GRAY, Color.GREEN)
            for ((width, height) in listOf(96f to 74f, 138f to 130f, 328f to 130f, 328f to 234f)) {
                val layout = hero.fit(width, height, 4f, 4f)
                assertEquals("No lost, reordered, hyphenated or split words", allWords, layout.lines.flatten().map { it.text })
                layout.lines.flatten().forEach {
                    assertEquals("Every hero run, including the delta, uses one size", layout.size, it.paint.textSize, 0f)
                }
                assertTrue("All glyphs fit horizontally: $font / $language / $count", layout.bounds.all { it.width() <= width })
                assertTrue("All glyphs fit vertically: $font / $language / $count", layout.height <= height)
            }
        }
    }

    @Test fun repeatedWordsCanAdaptToTheirOwnLines() {
        val words = "Nine Hundred Ninety Nine Million, Nine Hundred Ninety Nine Thousand, Nine Hundred and Ninety Nine Followers -3".split(" ")
        val hero = WidgetArtworkRenderer.followerHero(context,
            WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(fontFamily = TwidgetStore.FONT_GOOGLE_SANS_FLEX),
            words, words.size - 2, Color.BLACK, Color.GRAY, Color.RED)
        val layout = hero.fit(328f, 130f, 4f, 4f)
        val widths = layout.lines.flatten().filter { it.text == "Nine" }.map { it.paint.measureText(it.text) }
        assertTrue("Repeated words must be free to use different widths", widths.max() - widths.min() > 0.5f)
    }

    @Test fun supportingFlexTextStaysNarrowAndDeltaStaysRounded() {
        val words = listOf("Seven", "Thousand,", "Followers", "+21")
        val hero = WidgetArtworkRenderer.followerHero(context,
            WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(fontFamily = TwidgetStore.FONT_GOOGLE_SANS_FLEX),
            words, 2, Color.BLACK, Color.GRAY, Color.GREEN)
        for (width in listOf(138f, 328f, 500f)) {
            val runs = hero.fit(width, 130f, 4f, 4f).lines.flatten()
            assertTrue(runs[2].paint.fontVariationSettings!!.contains("'wdth' 69"))
            assertTrue(runs[3].paint.fontVariationSettings!!.contains("'wdth' 57"))
            assertTrue(runs[3].paint.fontVariationSettings!!.contains("'ROND' 100"))
            assertTrue(runs[2].paint.fontVariationSettings!!.contains("'wght' 200"))
            assertTrue(runs[3].paint.fontVariationSettings!!.contains("'wght' 200"))
            assertEquals(runs[0].paint.textSize, runs[3].paint.textSize, 0f)
        }
    }

    @Test fun oneUiHeroHasDistinctLightAndBoldRunsWithLightSupportingText() {
        val words = "Seven Thousand, Six Hundred and Seventy One Followers +21".split(" ")
        val hero = WidgetArtworkRenderer.followerHero(context,
            WidgetPreviews.settings(WidgetStyle.ONE_UI).copy(fontFamily = TwidgetStore.FONT_ONE_UI_SANS),
            words, words.size - 2, Color.BLACK, Color.GRAY, Color.GREEN)
        for (width in listOf(138f, 328f)) {
            val runs = hero.fit(width, 130f, 4f, 4f).lines.flatten()
            val weights = runs.map { Regex("'wght' (\\d+)").find(it.paint.fontVariationSettings!!)!!.groupValues[1].toInt() }
            val countWeights = weights.dropLast(2)
            assertTrue("Visible light/bold contrast: $weights", countWeights.max() - countWeights.min() >= 250)
            assertEquals(listOf(200, 200), weights.takeLast(2))
        }
    }

    @Test fun conjunctionsStayLightAcrossFontsWithoutChangingSize() {
        for (font in fonts) for (connector in listOf("and", "und")) {
            val words = listOf("Hundred", connector, "Seven", "Followers", "+21")
            val hero = WidgetArtworkRenderer.followerHero(context,
                WidgetPreviews.settings(WidgetStyle.ONE_UI).copy(fontFamily = font),
                words, 3, Color.BLACK, Color.GRAY, Color.GREEN)
            val runs = hero.fit(328f, 130f, 4f, 4f).lines.flatten()
            if (font == TwidgetStore.FONT_SYSTEM) {
                if (android.os.Build.VERSION.SDK_INT >= 28) assertEquals(200, runs[1].paint.typeface.weight)
            } else assertTrue(runs[1].paint.fontVariationSettings!!.contains("'wght' 200"))
            assertEquals(runs[0].paint.textSize, runs[1].paint.textSize, 0f)
        }
    }

    @Test fun hiddenAndZeroDeltasDoNotReserveHeroSpace() {
        val settings = WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(language = "en", showDelta = false)
        val stats = ProfileStats("Test", "twidget", 7671, 0, 0, 0)
        val hidden = WidgetArtworkRenderer.render(context, 352, 176, stats, settings,
            TwidgetWidget.LAYOUT_MODE_LARGE, false, 12345)
        val zero = WidgetArtworkRenderer.render(context, 352, 176, stats, settings.copy(showDelta = true),
            TwidgetWidget.LAYOUT_MODE_LARGE, false, 0)
        assertTrue(hidden.sameAs(zero))
        hidden.recycle()
        zero.recycle()
    }

    @Test fun shortCountsUseMoreSpacingWithoutSplittingWordsOrCrowdingTheFooter() {
        for (font in fonts) {
            fun layout(count: Long): FollowerHeroLayout.Layout {
                val words = TwidgetWidget.followersInWords(count, Locale.ENGLISH).split(" ")
                return WidgetArtworkRenderer.followerHero(context,
                    WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(fontFamily = font),
                    words + "Followers" + "+1", words.size, Color.BLACK, Color.GRAY, Color.GREEN)
                    .fit(328f, 124f, 6f, 4f)
            }
            val short = layout(116)
            val long = layout(999_999_999)
            assertTrue("Short counts need more leading: $font", short.lineGap > long.lineGap)
            assertTrue("Large text needs readable word spaces: $font", short.wordGap > long.wordGap)
            assertTrue("Spacing must respect footer clearance", short.height <= 124f)
            short.lines.flatten().forEach { assertEquals(short.size, it.paint.textSize, 0f) }
        }
    }

    @Test fun cachedFontStylesDoNotLeakColoursOrSizesBetweenWidgets() {
        val stats = ProfileStats("Test", "twidget", 116, 0, 0, 0)
        for (font in fonts) {
            val settings = WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(fontFamily = font, showDelta = true)
            val before = WidgetArtworkRenderer.render(context, 352, 176, stats, settings,
                TwidgetWidget.LAYOUT_MODE_LARGE, false, 1)
            WidgetArtworkRenderer.render(context, 162, 280, stats.copy(followersCount = 999_999_999), settings,
                TwidgetWidget.LAYOUT_MODE_LARGE, true, -3).recycle()
            val after = WidgetArtworkRenderer.render(context, 352, 176, stats, settings,
                TwidgetWidget.LAYOUT_MODE_LARGE, false, 1)
            assertTrue("Font-cache hits must preserve the entire artwork: $font", before.sameAs(after))
            before.recycle()
            after.recycle()
        }
    }

    @Test fun exportRefinedHeroPreviews() {
        val sheet = Bitmap.createBitmap(1104, 1258, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(65, 65, 65))
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f }
        fonts.forEachIndexed { column, font ->
            canvas.drawText(font, column * 368f + 8, 20f, label)
            for ((row, example) in listOf(352 to 7671L, 352 to 999_999_999L, 162 to 7671L,
                162 to 999_999_999L, 352 to 116L, 162 to 116L).withIndex()) {
                val style = if (column == 0) WidgetStyle.MATERIAL else WidgetStyle.ONE_UI
                val settings = WidgetPreviews.settings(style).copy(fontFamily = font,
                    language = "en", showDelta = true, containedFooter = row % 2 == 0,
                    logo = TwidgetStore.LOGO_TWITTER)
                val bitmap = WidgetArtworkRenderer.render(context, example.first, 176,
                    ProfileStats("Test", "thatjoshguy69", example.second, 0, 0, 0), settings,
                    TwidgetWidget.LAYOUT_MODE_LARGE, false, if (row % 2 == 0) 21 else -3, drawBackground = true)
                canvas.drawBitmap(bitmap, column * 368f + 8, row * 204f + 32, null)
                bitmap.recycle()
            }
        }
        File(base.cacheDir, "refined-hero-previews.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
    }
}
