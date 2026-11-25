package com.tjg.twidget.main

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks real window geometry without acquiring the shared UiAutomation connection. */
@RunWith(AndroidJUnit4::class)
class EdgeToEdgeInstrumentedTest {
    @Test fun pageViewportsReachBehindNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pages = listOf(
            MainActivity::class.java,
            AboutActivity::class.java,
            com.tjg.twidget.settings.SettingsActivity::class.java,
            com.tjg.twidget.settings.BriefSettingsActivity::class.java,
            com.tjg.twidget.settings.BriefContentSettingsActivity::class.java,
            com.tjg.twidget.settings.SettingsAdvancedActivity::class.java,
            com.tjg.twidget.notices.NoticesActivity::class.java,
        )
        for (page in pages) {
            val activity = instrumentation.startActivitySync(Intent(context, page)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
            try {
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    val decor = activity.window.decorView
                    val navigation = ViewCompat.getRootWindowInsets(decor)!!
                        .getInsets(WindowInsetsCompat.Type.navigationBars())
                    val content = activity.findViewById<View>(android.R.id.content)
                    val location = IntArray(2).also(content::getLocationInWindow)
                    assertEquals("${page.simpleName}: content reaches window bottom", decor.height,
                        location[1] + content.height)
                    @Suppress("DEPRECATION")
                    assertEquals("${page.simpleName}: transparent navigation", 0, activity.window.navigationBarColor)
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        assertFalse("${page.simpleName}: no navigation scrim", activity.window.isNavigationBarContrastEnforced)
                    }
                    if (page == MainActivity::class.java) {
                        val root = activity.findViewById<View>(R.id.dashboard_root)
                        assertEquals("Dashboard root must not reserve navigation space", 0, root.paddingBottom)
                        val scroll = activity.findViewById<View>(R.id.dashboard_scroll)
                        assertNotNull("Dashboard scroll is present", scroll)
                        val visible = android.graphics.Rect()
                        assertTrue(scroll.getGlobalVisibleRect(visible))
                        val windowPosition = IntArray(2).also(decor::getLocationOnScreen)
                        assertEquals("Dashboard visible viewport reaches navigation edge",
                            windowPosition[1] + decor.height, visible.bottom)
                        assertTrue("Navigation inset is present in this device configuration", navigation.bottom > 0)
                    }
                }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
    }
}
