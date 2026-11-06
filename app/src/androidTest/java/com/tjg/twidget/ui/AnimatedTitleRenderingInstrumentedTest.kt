package com.tjg.twidget.ui

import android.os.Build
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.main.AboutActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class AnimatedTitleRenderingInstrumentedTest {
    @Test fun aboutUsesHardwareGlyphsAndClearsBlurWhenFinished() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val title = activity.findViewById<AnimatedTitleView>(R.id.about_header_title)
                assertTrue(title.isHardwareAccelerated)
                assertTrue(title.childCount > 0)
                title.prepare()
                for (index in 0 until title.childCount) {
                    val glyph = title.getChildAt(index)
                    assertEquals(View.LAYER_TYPE_NONE, glyph.layerType)
                    assertEquals(0f, glyph.alpha, 0f)
                }
                title.play()
            }
            android.os.SystemClock.sleep(350)
            scenario.onActivity { activity ->
                val title = activity.findViewById<AnimatedTitleView>(R.id.about_header_title)
                assertTrue((0 until title.childCount).any { title.getChildAt(it).alpha > 0f })
                title.finish()
                for (index in 0 until title.childCount) {
                    val glyph = title.getChildAt(index)
                    assertEquals(1f, glyph.alpha, 0f)
                    assertEquals(0f, glyph.translationX, 0f)
                }
                val layers = (0 until title.childCount).map { title.getChildAt(it) }
                activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.about_app_bar)
                    .setExpanded(false, false)
                title.requestLayout()
                assertEquals(layers, (0 until title.childCount).map { title.getChildAt(it) })
            }
        }
    }
}
