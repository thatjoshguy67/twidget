package com.tjg.twidget.settings

import android.content.res.Configuration
import android.graphics.Bitmap
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

    @Test fun framesStayBoundedAndActionsRemainReadableAcrossCellSizes() {
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
                    for (rtl in listOf(false, true)) for (width in listOf(288, 352, 392, 720)) {
                        card.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        layout(card, width)
                        val images = descendants(card).filterIsInstance<ImageView>().filter { it.visibility == View.VISIBLE }
                        assertEquals("The device frame and live artwork are retained", 2, images.size)
                        assertNotNull(images.first().drawable)
                        assertTrue("The device never grows beyond its native design", images.first().width <= 306 && images.first().height <= 650)
                        assertTrue("The widget remains inside the device frame", images.last().width <= 262 && images.last().height <= 131)
                        assertTrue("A wide cell cannot magnify the card vertically", card.height <= 480)
                        val preview = bounds(images.last(), card)
                        val buttons = descendants(card).filterIsInstance<AppCompatButton>()
                        assertEquals(2, buttons.size)
                        for (button in buttons) {
                            val rect = bounds(button, card)
                            assertTrue("Actions retain native touch targets", rect.height() >= 48)
                            assertTrue("Actions stay within the card", rect.left >= 0 && rect.right <= width && rect.bottom <= card.height)
                            assertFalse("Controls do not cover the widget", Rect.intersects(preview, rect))
                            assertEquals("Complete action labels remain visible", button.text.length, button.layout.getLineEnd(button.lineCount - 1))
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
                assertTrue("Unavailable widgets retain the device frame", descendants(unavailable).filterIsInstance<ImageView>().any { it.drawable != null })
                assertTrue(descendants(unavailable).filterIsInstance<AppCompatButton>().isEmpty())
                assertTrue(descendants(unavailable).filterIsInstance<TextView>().any { it.text == context.getString(R.string.widget_one_ui_only) })
            }
        }
    }

    @Test fun phonesAndOneUiAlwaysUseASingleColumn() {
        for (width in listOf(360, 540, 720, 1100)) {
            assertEquals(1, WidgetSettingsLayoutManager.columns(width, 411, false, 1f))
            assertEquals(1, WidgetSettingsLayoutManager.columns(width, 800, true, 1f))
        }
        assertEquals(1, WidgetSettingsLayoutManager.columns(540, 800, false, 1f))
        assertEquals(2, WidgetSettingsLayoutManager.columns(720, 800, false, 1f))
        assertEquals(3, WidgetSettingsLayoutManager.columns(1100, 800, false, 1f))
        assertEquals("Large text receives wider cells", 1, WidgetSettingsLayoutManager.columns(800, 800, false, 1.6f))
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

    @Test fun lockPreviewScalesItsClockAndKeepsWidgetSamplesSeparate() {
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (oneUi in listOf(false, true)) for (fontScale in listOf(1f, 1.6f)) {
                val context = ContextThemeWrapper(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                    densityDpi = 160
                    this.fontScale = fontScale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }), R.style.Theme_Twidget)
                val card = WidgetSettingsCard(context, WidgetKind.LOCK_SCREEN, true, {}, {}, oneUi)
                val clock = descendants(card).filterIsInstance<android.widget.TextClock>().single()
                clock.text = "19:14"
                val date = descendants(card).filterIsInstance<TextView>().single {
                    it !is android.widget.TextClock && it.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                for (width in listOf(288, 352, 392)) {
                    layout(card, width)
                    TwidgetFonts.applyTo(card)
                    layout(card, width)
                    val scale = width / 392f
                    assertEquals("Clock follows the illustrated phone size", 56f * scale, clock.textSize, .01f)
                    assertEquals("Date follows the illustrated phone size", 14f * scale, date.textSize, .01f)
                    assertTrue("Date has its own clear line", date.bottom <= clock.top)
                    assertTrue("Clock lettering is not clipped", clock.layout.height <= clock.height)
                    val samples = descendants(card).filterIsInstance<ImageView>().filter { it.scaleType == ImageView.ScaleType.FIT_CENTER }
                    assertEquals(2, samples.size)
                    val wide = samples.maxBy { it.width }
                    val small = samples.minBy { it.width }
                    assertTrue("Space separates the clock from the widget samples", clock.bottom + 8 * scale <= wide.top)
                    assertEquals("Samples share a top edge", wide.top, small.top)
                    assertEquals("Samples share a bottom edge", wide.bottom, small.bottom)
                    assertEquals("The 1x1 sample is a circle", small.width, small.height)
                    assertTrue("Samples have a clear gutter", small.left - wide.right >= 10 * scale)
                    for (sample in samples) {
                        val radius = (sample.background as android.graphics.drawable.GradientDrawable).cornerRadius
                        assertEquals("Capsules scale with the phone", sample.height / 2f, radius, 1f)
                    }
                    if (oneUi && fontScale == 1f && width == 352) {
                        val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                        card.draw(android.graphics.Canvas(bitmap))
                        File(base.cacheDir, "lock-preview-clean-${if (night) "dark" else "light"}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test fun materialSettingsUsesGridOnlyForWideLargeScreenWindows() {
        assumeFalse(TwidgetFonts.hasSystemOneUiSans)
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(base, SettingsPage.WIDGETS)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat
                val cards = WidgetKind.entries.map { kind ->
                    fragment.findPreference<LayoutPreference>("widget_card_${kind.name.lowercase()}")!!.getView()!!
                }
                val manager = fragment.listView.layoutManager as WidgetSettingsLayoutManager
                val list = fragment.listView
                val density = activity.resources.displayMetrics.density
                val config = activity.resources.configuration
                val expected = WidgetSettingsLayoutManager.columns(((list.width - list.paddingLeft - list.paddingRight) / density).toInt(),
                    config.smallestScreenWidthDp, false, config.fontScale)
                assertEquals(expected, manager.spanCount)
                if (expected >= 2) {
                    val first = Rect().also { cards[0].getGlobalVisibleRect(it) }
                    val second = Rect().also { cards[1].getGlobalVisibleRect(it) }
                    assertEquals("Supported widget cards share a row", first.top, second.top)
                    assertTrue("Cells remain separate", first.right <= second.left || second.right <= first.left)
                    cards.take(2).forEach { assertTrue("Both framed cards are visible", it.getGlobalVisibleRect(Rect())) }
                }
                cards.forEach { assertTrue("Card artwork stays bounded", it.measuredHeight <= 480 * density) }
            }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(base.cacheDir, "widget-settings-material-grid.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
