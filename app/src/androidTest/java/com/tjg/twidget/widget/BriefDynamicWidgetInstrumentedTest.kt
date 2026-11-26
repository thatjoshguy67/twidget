package com.tjg.twidget.widget

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ViewFlipper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefDynamicWidgetInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun settings() = TwidgetStore.widgetSettings(context, kind = WidgetKind.BRIEF).copy(
        style = WidgetStyle.ONE_UI, briefDynamic = true, tintAlpha = 102,
        briefBackgroundEnabled = true,
        briefPrimaryColor = Color.BLUE, briefSecondaryColor = Color.CYAN,
    )

    @Test fun gradientStaysStaticAndRespectsOpacityWithoutFillingStandaloneRoundedCorners() {
        val settings = settings()
        val first = BriefWidgetDynamicBackground.render(context, settings, 352, 176, false, 0f, 100_000)
        val second = BriefWidgetDynamicBackground.render(context, settings, 352, 176, false, .5f, 100_000)
        val opaque = BriefWidgetDynamicBackground.render(context, settings.copy(tintAlpha = 240), 352, 176, false, 0f, 100_000)
        assertTrue("The widget gradient does not animate or cycle", first.sameAs(second))
        assertEquals(1, BriefWidgetDynamicBackground.frameCount())
        assertEquals(0, Color.alpha(first.getPixel(0, 0)))
        assertTrue(Color.alpha(opaque.getPixel(opaque.width / 2, opaque.height / 2)) >
            Color.alpha(first.getPixel(first.width / 2, first.height / 2)))
        assertTrue(first.allocationByteCount <= 100_000)
        first.recycle(); second.recycle(); opaque.recycle()
        assertTrue(BriefWidgetDynamicBackground.enabled(settings.copy(style = WidgetStyle.MATERIAL)))
        assertFalse(BriefWidgetDynamicBackground.enabled(settings.copy(briefBackgroundEnabled = false)))
    }

    @Test fun automaticColoursFollowTheLocalDayPartAndIgnoreOldFixedColourOverrides() {
        for (dark in listOf(false, true)) {
            val morning = java.time.ZonedDateTime.parse("2026-11-26T09:00:00Z")
            val evening = java.time.ZonedDateTime.parse("2026-11-26T19:00:00Z")
            val settings = settings()
            val first = BriefWidgetDynamicBackground.render(context, settings, 352, 176, dark, .25f, 100_000, now = morning)
            val later = BriefWidgetDynamicBackground.render(context, settings, 352, 176, dark, .25f, 100_000, now = evening)
            val oldOverride = BriefWidgetDynamicBackground.render(context, settings.copy(briefPrimaryColor = Color.RED),
                352, 176, dark, .25f, 100_000, now = morning)
            assertFalse("The widget actually changes colour with the time of day", first.sameAs(later))
            assertTrue("Dynamic colour is automatic", first.sameAs(oldOverride))
            first.recycle(); later.recycle(); oldOverride.recycle()
        }
    }

    @Test fun remoteViewsKeepTheRoundedLauncherSurfaceAndItsOpacityWithDynamicFramesAboveIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val id = 987654321
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, android.content.Context.MODE_PRIVATE)
        assumeTrue(prefs.all.keys.none { it.endsWith("_$id") })
        try {
            TwidgetStore.saveWidgetSettings(context, id, settings(), WidgetKind.BRIEF)
            val restored = TwidgetStore.widgetSettings(context, id, WidgetKind.BRIEF)
            assertTrue(restored.briefDynamic)
            assertEquals(Color.BLUE, restored.briefPrimaryColor)
            assertEquals(Color.CYAN, restored.briefSecondaryColor)
            for ((width, height) in listOf(352 to 76, 352 to 176, 162 to 176)) {
                val views = TwidgetBriefWidget.createViews(context, id, width, height, "", null)
                instrumentation.runOnMainSync {
                    val root = views.apply(context, FrameLayout(context))
                    val surface = root.findViewById<android.view.View>(android.R.id.background)
                    assertTrue("Native rounded drawable must remain for One UI blur", surface.background is GradientDrawable)
                    assertFalse("Keep launcher corner enforcement enabled", surface.clipToOutline)
                    assertEquals("No second app-defined radius under the launcher's mask", 0f, (surface.background as GradientDrawable).cornerRadius, 0f)
                    assertEquals(102, Color.alpha(surface.backgroundTintList!!.defaultColor))
                    assertNotNull(root.findViewById<ImageView>(R.id.brief_widget_artwork).drawable)
                    repeat(BriefWidgetDynamicBackground.frameCount()) { frame ->
                        val image = root.findViewById<ImageView>(BriefWidgetDynamicBackground.frameIds[frame])
                        assertNotNull(image.drawable)
                        val texture = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                        assertTrue("The texture has no second pre-scaled rounded mask", Color.alpha(texture.getPixel(0, 0)) > 0)
                    }
                    assertNull("No animated flipper is sent to the launcher", root.findViewById<ViewFlipper>(R.id.brief_widget_background_flipper))
                }
            }
            TwidgetStore.saveWidgetSettings(context, id, settings().copy(briefBackgroundEnabled = false), WidgetKind.BRIEF)
            assertFalse(TwidgetStore.widgetSettings(context, id, WidgetKind.BRIEF).briefBackgroundEnabled)
            instrumentation.runOnMainSync {
                val root = TwidgetBriefWidget.createViews(context, id, 352, 176, "", null).apply(context, FrameLayout(context))
                val surface = root.findViewById<android.view.View>(android.R.id.background)
                assertEquals(0, Color.alpha(surface.backgroundTintList!!.defaultColor))
                assertNotNull(root.findViewById<ImageView>(R.id.brief_widget_artwork).drawable)
                assertNull(root.findViewById<android.view.View>(R.id.brief_widget_background_0))
                assertEquals(1f, surface.alpha, 0f)
            }
        } finally {
            prefs.edit().apply { prefs.all.keys.filter { it.endsWith("_$id") }.forEach(::remove) }.commit()
        }
    }
}
