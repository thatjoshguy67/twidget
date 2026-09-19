package com.tjg.twidget.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsIconsInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val icons = listOf(
        R.drawable.settings_icon_accounts, R.drawable.settings_icon_appearance,
        R.drawable.settings_icon_data, R.drawable.settings_icon_brief,
        R.drawable.settings_icon_notifications, R.drawable.settings_icon_schedule,
        R.drawable.settings_icon_language, R.drawable.settings_icon_about,
        R.drawable.settings_icon_debug,
    )

    @Test fun paletteRecoloursEveryBackgroundAndKeepsGlyphsVisibleWithoutChangingDefaults() {
        for (icon in icons) {
            val original = render(SettingsIcons.load(context, icon, null))
            for (accent in listOf(0xFFB7A1D8.toInt(), 0xFF392355.toInt())) {
                val themed = render(SettingsIcons.load(context, icon, accent))
                // The very top of the disc is clear of every glyph, including Brief's gradient.
                assertEquals("Icon $icon background", accent, themed.getPixel(48, 6))
                val glyph = if (accent == 0xFFB7A1D8.toInt()) Color.BLACK else Color.WHITE
                assertTrue("Icon $icon must retain a contrasting glyph", (24 until 72).any { y ->
                    (24 until 72).any { x -> themed.getPixel(x, y) == glyph }
                })
                assertEquals(Color.TRANSPARENT, themed.getPixel(0, 0))
            }
            val restored = render(SettingsIcons.load(context, icon, null))
            assertTrue("Icon $icon default changed after tinting", original.sameAs(restored))
        }
        assertEquals(0xFF387AFF.toInt(), render(SettingsIcons.load(context,
            R.drawable.settings_icon_accounts, null)).getPixel(48, 6))
    }

    private fun render(drawable: Drawable): Bitmap =
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
}
