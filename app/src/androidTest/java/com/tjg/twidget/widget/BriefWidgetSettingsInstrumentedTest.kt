package com.tjg.twidget.widget

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import com.tjg.twidget.R
import dev.oneuiproject.oneui.widget.RadioItemViewGroup
import dev.oneuiproject.oneui.widget.SwitchItemView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefWidgetSettingsInstrumentedTest {
    @Test fun bothStylesOfferBasicOrAutomaticColourAndBackgroundOffKeepsTheChoice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(WidgetConfigActivity.EXTRA_DEFAULT_KIND, WidgetKind.BRIEF.name)
        // No Save: exercise the editor without changing the user's widget or defaults.
        ActivityScenario.launch<WidgetConfigActivity>(intent).use { scenario ->
            scenario.onActivity { page ->
                val background = page.findViewById<SwitchItemView>(R.id.brief_background_row)
                assertFalse("No divider across the standalone capsule", background.showTopDivider)
                assertFalse(page.findViewById<dev.oneuiproject.oneui.widget.RadioItemView>(R.id.brief_background_basic).showTopDivider)
                val types = page.findViewById<RadioItemViewGroup>(R.id.brief_background_type_group)
                background.isChecked = true
                for (style in 0..1) {
                    page.findViewById<ViewPager>(R.id.widget_style_pager).setCurrentItem(style, false)
                    assertEquals(View.VISIBLE, page.findViewById<View>(R.id.brief_background_controls).visibility)
                    assertEquals(View.VISIBLE, page.findViewById<View>(R.id.brief_background_type_card).visibility)
                    types.check(R.id.brief_background_dynamic)
                    assertFalse(page.findViewById<View>(R.id.tint_row).isEnabled)
                    assertEquals(View.VISIBLE, page.findViewById<View>(R.id.opacity_block).visibility)
                    background.isChecked = false
                    assertEquals(View.GONE, page.findViewById<View>(R.id.brief_background_type_card).visibility)
                    assertEquals(View.GONE, page.findViewById<View>(R.id.opacity_block).visibility)
                    val preview = page.findViewById<ViewGroup>(R.id.preview_widget)
                    assertEquals(0, Color.alpha((preview.background as GradientDrawable).color!!.defaultColor))
                    background.isChecked = true
                    assertFalse("Disabling the background preserves Dynamic colour", page.findViewById<View>(R.id.tint_row).isEnabled)
                    types.check(R.id.brief_background_basic)
                    assertTrue(page.findViewById<View>(R.id.tint_row).isEnabled)
                }
            }
        }
    }
}
