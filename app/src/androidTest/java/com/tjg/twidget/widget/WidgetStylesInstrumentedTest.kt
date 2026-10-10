package com.tjg.twidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.brief.*
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.data.TwidgetWidgetSettings
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetStylesInstrumentedTest {
    @Test fun renderFigmaFixturesAndVerifyOpaqueDynamicSurfaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        val sizes = listOf(162 to 76, 352 to 76, 162 to 176, 352 to 176)
        val sheet = Bitmap.createBitmap(px(1120), px(1080), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.DKGRAY)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = px(14).toFloat() }
        for ((row, pair) in listOf(WidgetStyle.MATERIAL to false, WidgetStyle.MATERIAL to true,
            WidgetStyle.ONE_UI to false, WidgetStyle.ONE_UI to true).withIndex()) {
            val (style, dark) = pair
            val settings = TwidgetWidgetSettings(80, 0, TwidgetStore.LOGO_TWITTER, TwidgetStore.TAP_REFRESH,
                "thatjoshguy69", "system", style.defaultFont, false, "en", style)
            val colors = WidgetColors.resolve(context, settings, dark)
            if (style == WidgetStyle.MATERIAL) {
                assertEquals(255, Color.alpha(colors.background))
                assertEquals(colors, WidgetColors.resolve(context, settings.copy(tintAlpha = 240), dark))
                assertNotEquals(colors.primary, colors.background)
            }
            val y = row * 265 + 24
            canvas.drawText("${style.name} / ${if (dark) "dark" else "light"}", px(12).toFloat(), px(y - 5).toFloat(), labelPaint)
            var x = 12
            sizes.forEach { (w, h) ->
                val mode = if (h <= 110) { if (w < 230) TwidgetWidget.LAYOUT_MODE_COMPACT_2X1 else TwidgetWidget.LAYOUT_MODE_COMPACT_STRIP }
                    else if (w < 230) TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE else TwidgetWidget.LAYOUT_MODE_LARGE
                val art = WidgetArtworkRenderer.render(context, px(w), px(h), ProfileStats("Test", "thatjoshguy69", 7671, 0, 0, 0),
                    settings, mode, dark, drawBackground = true)
                canvas.drawBitmap(art, px(x).toFloat(), px(y).toFloat(), null)
                art.recycle()
                x += w + 16
            }
            val snapshot = BriefSnapshot("test", 0, 0, 0, 0, 7671, 0, 0, 0, 0,
                listOf(BriefCard("fixture", BriefCardType.SLOWDOWN, "Slowing down", "Keep working to hit your 8,000 follower goal", 0)),
                headline = "Slowing down", subheading = "Keep working to hit your 8,000 follower goal", language = BriefStrings.from(context).languageTag, shortDescription = "Keep working to hit your 8,000 follower goal", topFollowerRanks = emptyMap())
            // A separate sheet below is used for Brief so its larger layouts remain full resolution.
            val briefSheet = Bitmap.createBitmap(px(1120), px(610), Bitmap.Config.ARGB_8888)
            val briefCanvas = Canvas(briefSheet).apply { drawColor(Color.DKGRAY) }
            val fixtures = listOf(snapshot,
                snapshot.copy(headline = "Getting attention", shortDescription = "Your last tweet got over 100K impressions!"),
                snapshot.copy(headline = "New top follower", shortDescription = "@JohnCena ranks as your #2 top follower"))
            fixtures.forEachIndexed { index, fixture ->
            x = 12
            sizes.forEach { (w, h) ->
                val art = BriefWidgetArtworkRenderer.render(context, px(w), px(h), "test", fixture, dark,
                    style.defaultFont, style = style, background = colors.background)
                briefCanvas.drawBitmap(art, px(x).toFloat(), px(12 + 198 * index).toFloat(), null)
                art.recycle()
                x += w + 16
            }
            }
            File(context.cacheDir, "brief-${style.name}-$dark.png").outputStream().use { briefSheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
            briefSheet.recycle()
        }
        File(context.cacheDir, "widget-styles.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sheet.recycle()
    }

    @Test fun realHostAndConfigurationUseTheChosenStyle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val host = android.appwidget.AppWidgetHost(context, 97531)
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            for (brief in listOf(false, true)) {
                val id = host.allocateAppWidgetId()
                val provider = android.content.ComponentName(context,
                    if (brief) TwidgetBriefWidget::class.java else com.tjg.twidget.TwidgetWidget::class.java)
                val options = android.os.Bundle().apply {
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 352)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 352)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 176)
                    putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 176)
                    putParcelableArrayList(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        arrayListOf(android.util.SizeF(352f, 176f)))
                }
                assertTrue("Bind test widget", manager.bindAppWidgetIdIfAllowed(id, provider, options))
                val selected = TwidgetStore.widgetSettings(context, id).copy(
                    style = WidgetStyle.MATERIAL, fontFamily = TwidgetStore.FONT_GOOGLE_SANS_FLEX)
                TwidgetStore.saveWidgetSettings(context, id, selected)
                if (brief) TwidgetBriefWidget.updateWidget(context, manager, id)
                else TwidgetWidget.updateWidget(context, manager, id)
                val activity = instrumentation.startActivitySync(android.content.Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as WidgetConfigActivity
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(com.tjg.twidget.R.id.opacity_block).visibility)
                    val preview = activity.findViewById<android.widget.FrameLayout>(com.tjg.twidget.R.id.preview_widget)
                    assertEquals(2f, preview.layoutParams.width.toFloat() / preview.layoutParams.height, .02f)
                }
                instrumentation.runOnMainSync {
                    val row = activity.findViewById<android.view.View>(com.tjg.twidget.R.id.widget_style_row)
                    val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(com.tjg.twidget.R.id.widget_settings_scroll)
                    val rect = android.graphics.Rect(0, 0, row.width, row.height)
                    scroll.offsetDescendantRectToMyCoords(row, rect)
                    scroll.scrollTo(0, rect.top - (40 * context.resources.displayMetrics.density).toInt())
                }
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
                    File(context.cacheDir, "settings-material-${if (brief) "brief" else "followers"}.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                instrumentation.runOnMainSync {
                    val row = activity.findViewById<android.view.View>(com.tjg.twidget.R.id.widget_style_row)
                    assertTrue(row.findViewById<android.view.View>(dev.oneuiproject.oneui.design.R.id.cardview_container).performClick())
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    fun findList(view: android.view.View): android.widget.AdapterView<*>? {
                        if (view is android.widget.AdapterView<*>) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    val list = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { findList(it) }
                    assertNotNull("Style popup is visible", list)
                    list!!.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))
                    assertEquals(android.view.View.VISIBLE, activity.findViewById<android.view.View>(com.tjg.twidget.R.id.opacity_block).visibility)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val slider = activity.findViewById<androidx.appcompat.widget.SeslSeekBar>(com.tjg.twidget.R.id.opacity_slider)
                    val tickId = listOf(com.tjg.twidget.R.id.opacity_tick_0, com.tjg.twidget.R.id.opacity_tick_1,
                        com.tjg.twidget.R.id.opacity_tick_2, com.tjg.twidget.R.id.opacity_tick_3)[slider.progress]
                    val tick = activity.findViewById<android.view.View>(tickId)
                    val thumb = activity.findViewById<android.view.View>(com.tjg.twidget.R.id.opacity_thumb_visual)
                    assertTrue(tick.width > 0)
                    assertEquals(tick.x + tick.width / 2f, thumb.x + thumb.width / 2f, 1f)
                }
                instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
                    File(context.cacheDir, "settings-${if (brief) "brief" else "followers"}.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                instrumentation.runOnMainSync { activity.findViewById<android.view.View>(com.tjg.twidget.R.id.btn_save).performClick() }
                instrumentation.waitForIdleSync()
                assertEquals(WidgetStyle.ONE_UI, TwidgetStore.widgetSettings(context, id).style)
                assertEquals(TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.widgetSettings(context, id).fontFamily)
                host.deleteAppWidgetId(id)
            }
        } finally {
            host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun styleAndCustomFontRoundTripWithoutLosingGlassOpacity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = 987654
        val original = TwidgetStore.widgetSettings(context, id)
        try {
            val selected = original.copy(style = WidgetStyle.MATERIAL, fontFamily = TwidgetStore.FONT_SYSTEM, tintAlpha = 80)
            TwidgetStore.saveWidgetSettings(context, id, selected)
            assertEquals(selected, TwidgetStore.widgetSettings(context, id))
            TwidgetStore.saveWidgetSettings(context, id, selected.copy(style = WidgetStyle.ONE_UI))
            assertEquals(80, TwidgetStore.widgetSettings(context, id).tintAlpha)
        } finally { TwidgetStore.saveWidgetSettings(context, id, original) }
    }
}
