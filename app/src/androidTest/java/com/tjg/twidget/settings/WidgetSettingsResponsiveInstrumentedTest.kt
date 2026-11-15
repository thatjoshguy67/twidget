package com.tjg.twidget.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatButton
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.widget.WidgetKind
import dev.oneuiproject.oneui.preference.LayoutPreference
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetSettingsResponsiveInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val base get() = instrumentation.targetContext
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
    private fun bounds(view: View, root: ViewGroup): Rect = Rect(0, 0, view.width, view.height).also {
        root.offsetDescendantRectToMyCoords(view, it)
    }

    @Test fun materialCardsStayCompactAndKeepControlsReadableAcrossScreenSizes() {
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (fontScale in listOf(1f, 1.6f)) {
                val context = ContextThemeWrapper(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    densityDpi = 160
                    this.fontScale = fontScale
                    setLocale(Locale.GERMAN)
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }), R.style.Theme_Twidget)
                for (kind in listOf(WidgetKind.FOLLOWERS, WidgetKind.BRIEF)) {
                    var edits = 0
                    var adds = 0
                    val card = WidgetSettingsCard(context, kind, true, { edits++ }, { adds++ }, oneUi = false)
                    TwidgetFonts.applyTo(card)
                    for (rtl in listOf(false, true)) for (width in listOf(320, 540, 720, 1100)) {
                        card.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        layout(card, width)
                        val image = descendants(card).filterIsInstance<ImageView>().single { it.visibility == View.VISIBLE }
                        val preview = bounds(image, card)
                        assertTrue("Preview stays at native widget size", image.width <= 352 && image.height <= 176)
                        assertTrue("The widget preserves its 2:1 proportions", kotlin.math.abs(image.width - image.height * 2) <= 1)
                        assertTrue("No enlarged phone mockup remains", descendants(card).filterIsInstance<ImageView>().count { it.visibility == View.VISIBLE } == 1)
                        assertTrue("The whole settings card is bounded on wide pages", card.height <= if (fontScale == 1f) 360 else 440)
                        val buttons = descendants(card).filterIsInstance<AppCompatButton>()
                        assertEquals(2, buttons.size)
                        for (button in buttons) {
                            val rect = bounds(button, card)
                            assertTrue("Native touch target stays at least 48dp", rect.height() >= 48)
                            assertTrue("Actions remain inside the card", rect.left >= 16 && rect.right <= width - 16 && rect.top >= 16 && rect.bottom <= card.height - 16)
                            assertFalse("Actions never cover the widget preview", Rect.intersects(preview, rect))
                            assertEquals("Action lettering stays native sized", android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16f, context.resources.displayMetrics), button.textSize, 1f)
                            assertEquals("The complete action label is visible", button.text.length, button.layout.getLineEnd(button.lineCount - 1))
                            assertTrue((0 until button.lineCount).all { button.layout.getEllipsisCount(it) == 0 })
                            button.performClick()
                        }
                    }
                    assertEquals(8, edits)
                    assertEquals(8, adds)
                }
                val unavailable = WidgetSettingsCard(context, WidgetKind.LOCK_SCREEN, false,
                    { fail("Unsupported edit") }, { fail("Unsupported add") }, oneUi = false)
                layout(unavailable, 720)
                assertTrue("Unavailable widgets use a compact explanatory row", unavailable.height < 180)
                assertTrue(descendants(unavailable).filterIsInstance<ImageView>().none { it.visibility == View.VISIBLE })
                assertTrue(descendants(unavailable).filterIsInstance<AppCompatButton>().isEmpty())
                assertTrue(descendants(unavailable).filterIsInstance<TextView>().any {
                    it.visibility == View.VISIBLE && it.text == context.getString(R.string.widget_one_ui_only)
                })
            }
        }
    }

    @Test fun oneUiCardsKeepTheirExistingDeviceComposition() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                densityDpi = 160
            }), R.style.Theme_Twidget)
            val card = WidgetSettingsCard(context, WidgetKind.FOLLOWERS, true, {}, {}, oneUi = true)
            layout(card, 392)
            assertEquals(384, card.height)
            val images = descendants(card).filterIsInstance<ImageView>().filter { it.visibility == View.VISIBLE }
            assertEquals(2, images.size)
            assertTrue(images.any { it.width == 306 && it.height == 650 })
        }
    }

    @Test fun unfoldedMaterialSettingsShowsBothWidgetKindsWithoutAnOversizedPreview() {
        assumeFalse(TwidgetFonts.hasSystemOneUiSans)
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(base, SettingsPage.WIDGETS)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat
                val cards = WidgetKind.entries.map { kind ->
                    fragment.findPreference<LayoutPreference>("widget_card_${kind.name.lowercase()}")!!.getView()!!
                }
                val widthDp = cards.first().width / activity.resources.displayMetrics.density
                if (widthDp >= 600) {
                    cards.take(2).forEach { assertTrue("Wide supported cards stay under 240dp", it.height < activity.dp(240)) }
                    assertTrue("Both supported cards are visible without scrolling", cards.take(2).all { card ->
                        val rect = Rect()
                        card.getGlobalVisibleRect(rect) && rect.height() >= card.height - 1
                    })
                }
                assertTrue("Unsupported lock-screen row stays compact", cards.last().measuredHeight < activity.dp(180))
            }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(base.cacheDir, "widget-settings-material-responsive.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private fun android.content.Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
