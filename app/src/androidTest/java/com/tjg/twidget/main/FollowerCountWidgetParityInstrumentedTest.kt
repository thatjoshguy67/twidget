package com.tjg.twidget.main

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.AppAppearance
import com.tjg.twidget.ui.DashboardCardDesign
import com.tjg.twidget.ui.FollowerCountCardView
import com.tjg.twidget.widget.WidgetArtworkRenderer
import com.tjg.twidget.widget.WidgetPreviews
import com.tjg.twidget.widget.WidgetStyle
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowerCountWidgetParityInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private fun context(dark: Boolean): Context = ContextThemeWrapper(base.createConfigurationContext(
        Configuration(base.resources.configuration).apply {
            densityDpi = 160
            fontScale = 1f
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }), R.style.Theme_Twidget)

    private fun draw(card: View, width: Int, height: Int): Bitmap {
        DashboardCardDesign.apply(card, height, width)
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { card.draw(Canvas(it)) }
    }

    @Test fun bothSpansMatchWidgetTypographyAndFittingWithoutAnAccountFooter() {
        val originalFont = AppAppearance.font(base)
        try {
            instrumentation.runOnMainSync {
                for (font in AppAppearance.Font.entries) {
                    AppAppearance.setFont(base, font)
                    val family = when (font) {
                        AppAppearance.Font.DEFAULT -> TwidgetStore.FONT_ONE_UI_SANS
                        AppAppearance.Font.SYSTEM -> TwidgetStore.FONT_SYSTEM
                        AppAppearance.Font.GOOGLE_SANS_FLEX -> TwidgetStore.FONT_GOOGLE_SANS_FLEX
                    }
                    for (dark in listOf(false, true)) {
                        val context = context(dark)
                        val settings = WidgetPreviews.settings(WidgetStyle.MATERIAL).copy(fontFamily = family,
                            language = AppLocales.applicationLocale().toLanguageTag(), showDelta = true)
                        for (count in listOf(116L, 8079L, 999_999_999L)) {
                            val stats = ProfileStats("Test", "not_in_the_card", count, 1, 1, 1)
                            val card = FollowerCountCard.create(context, stats, 21, 1) as FollowerCountCardView
                            card.background = null
                            for ((width, height) in listOf(148 to 160, 320 to 160, 640 to 160)) {
                                val actual = draw(card, width, height)
                                val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                val (words, countWordCount) = WidgetArtworkRenderer.followerHeroWords(context, settings, stats, 21)
                                val hero = WidgetArtworkRenderer.followerHero(context, settings, words, countWordCount,
                                    context.getColor(R.color.oneui_text_primary), context.getColor(R.color.oneui_text_secondary),
                                    context.getColor(R.color.dashboard_count_editorial_delta))
                                val availableWidth = (width - 24).toFloat()
                                hero.draw(Canvas(expected), availableWidth, (height - 24).toFloat(), 12f,
                                    WidgetArtworkRenderer.wordSpacing(context, availableWidth), 4f)
                                assertTrue("Card must use the widget's actual layout and font axes: $font/$dark/$count/$width", actual.sameAs(expected))
                                val fitted = requireNotNull(card.fittedLayout())
                                assertSame("A stable frame reuses its fitted runs", fitted, card.fittedLayout())
                                assertTrue(fitted.bounds.all { it.width() <= availableWidth })
                                assertTrue(fitted.height <= height - 24)
                                assertEquals(words, fitted.lines.flatten().map { it.text })
                                assertFalse(card.contentDescription.toString().contains(stats.userName))
                                actual.recycle(); expected.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppAppearance.setFont(base, originalFont) }
        }
    }

    @Test fun redrawAfterUpdatesAndResizeKeepsTheCompleteComposition() {
        instrumentation.runOnMainSync {
            val context = context(true)
            val stats = ProfileStats("Test", "hidden_handle", 8079, 1, 1, 1)
            val card = FollowerCountCard.create(context, stats, 5, 1) as FollowerCountCardView
            val sheet = Bitmap.createBitmap(1032, 416, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(sheet).apply { drawColor(Color.rgb(40, 40, 40)) }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f }
            for ((index, width) in listOf(148, 320, 500).withIndex()) {
                val image = draw(card, width, 160)
                val x = listOf(12f, 172f, 504f)[index]
                canvas.drawText("${width}dp", x, 20f, label)
                canvas.drawBitmap(image, x, 28f, null)
                image.recycle()
            }
            val prior = card.fittedLayout()
            assertSame(card, FollowerCountCard.create(context, stats.copy(followersCount = 116), -3, 2, card))
            draw(card, 500, 160).also { canvas.drawBitmap(it, 12f, 220f, null); it.recycle() }
            assertNotSame(prior, card.fittedLayout())
            assertTrue(card.contentDescription.toString().contains("-3"))
            FollowerCountCard.create(context, stats.copy(followersKnown = false), 5, 1, card)
            draw(card, 320, 160).also { it.recycle() }
            assertEquals(listOf("--", context.getString(R.string.followers)), card.fittedLayout()!!.lines.flatten().map { it.text })
            assertFalse(card.contentDescription.toString().contains("+5"))
            File(base.cacheDir, "follower-count-card-widget-layout.png").outputStream().use {
                sheet.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            sheet.recycle()
        }
    }
}
