package com.tjg.twidget.ui

import android.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingKeyboardInstrumentedTest {
    @Test fun settingsTraversalAndShortcutHelpUseSharedWindowHandling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        androidx.test.core.app.ActivityScenario.launch(com.tjg.twidget.settings.SettingsActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            instrumentation.setInTouchMode(false)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val focused = activity.currentFocus
                assertNotNull("Tab reaches a settings control", focused)
                if (focused!!.isClickable) assertNotNull("Settings control has the shared focus treatment", focused.getTag(R.id.floating_focus_binding))
                assertTrue("Shortcut help is handled", activity.window.callback.dispatchKeyEvent(android.view.KeyEvent(
                    0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_SLASH, 0, android.view.KeyEvent.META_CTRL_ON)))
            }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.setInTouchMode(true)
        }
    }

    @Test fun focusCompositionIsIdempotentAndInstantMotionSettles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            val button = AppCompatButton(context)
            SeslKeyboardFocus.install(button, 12f)
            val foreground = button.foreground
            repeat(20) { SeslKeyboardFocus.install(button, 12f) }
            assertSame("Repeated layout must not stack rings", foreground, button.foreground)
            var value = -1f
            val motion = FloatingSurfaceMotion(0f) { value = it }
            motion.moveTo(1f, false); assertEquals(1f, value, 0f)
            motion.moveTo(0f, false); assertEquals(0f, value, 0f)
        }
    }
}
