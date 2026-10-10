package com.tjg.twidget.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.snackbar.Snackbar
import com.tjg.twidget.R
import com.tjg.twidget.schedule.ScheduleComposeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TwidgetSnackbarInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun nativeActionIsVisibleAboveComposerAndRunsOnce() {
        ActivityScenario.launch<ScheduleComposeActivity>(Intent(context, ScheduleComposeActivity::class.java)).use { scenario ->
            var clicked = 0
            lateinit var snackbar: Snackbar
            val shown = CountDownLatch(1)
            scenario.onActivity { activity ->
                snackbar = TwidgetSnackbar(activity).show(
                    activity.getString(R.string.schedule_media_save_failed),
                    activity.findViewById(R.id.schedule_compose_bottom_bar),
                    activity.getString(R.string.notices_retry),
                ) { clicked++ }!!
                snackbar.addCallback(object : Snackbar.Callback() {
                    override fun onShown(sb: Snackbar) { shown.countDown() }
                })
            }
            assertTrue(shown.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            awaitPlacement(scenario, snackbar)
            scenario.onActivity { activity ->
                val bar = bounds(snackbar.view.findViewById(com.google.android.material.R.id.snackbar_content_layout))
                val controls = bounds(activity.findViewById(R.id.schedule_compose_bottom_bar))
                assertTrue("Snackbar $bar overlaps controls $controls", bar.bottom <= controls.top)
                val action = snackbar.view.findViewById<Button>(com.google.android.material.R.id.snackbar_action)
                assertTrue(action.isShown)
                assertTrue(bar.contains(bounds(action)))
                assertTrue(snackbar.messageView.isShown)
            }
            capture("action")
            scenario.onActivity {
                snackbar.view.findViewById<Button>(com.google.android.material.R.id.snackbar_action).performClick()
                assertEquals(1, clicked)
            }
        }
    }

    @Test fun longMessageFollowsMovingAnchorAndStopsWithActivity() {
        ActivityScenario.launch<ScheduleComposeActivity>(Intent(context, ScheduleComposeActivity::class.java)).use { scenario ->
            lateinit var feedback: TwidgetSnackbar
            lateinit var snackbar: Snackbar
            val shown = CountDownLatch(1)
            scenario.onActivity { activity ->
                feedback = TwidgetSnackbar(activity)
                snackbar = feedback.show(
                    "Buffer konnte nicht synchronisiert werden. Bitte überprüfe deine Verbindung und versuche es erneut.",
                    activity.findViewById(R.id.schedule_compose_bottom_bar), "Erneut versuchen", {},
                )!!
                snackbar.addCallback(object : Snackbar.Callback() {
                    override fun onShown(sb: Snackbar) { shown.countDown() }
                })
            }
            assertTrue(shown.await(5, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val anchor = activity.findViewById<View>(R.id.schedule_compose_bottom_bar)
                anchor.layoutParams = (anchor.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    bottomMargin += (120 * activity.resources.displayMetrics.density).toInt()
                }
            }
            instrumentation.waitForIdleSync()
            awaitPlacement(scenario, snackbar)
            scenario.onActivity { activity ->
                val bar = bounds(snackbar.view.findViewById(com.google.android.material.R.id.snackbar_content_layout))
                val anchor = bounds(activity.findViewById(R.id.schedule_compose_bottom_bar))
                assertTrue("Snackbar must follow its anchor", bar.bottom <= anchor.top)
                val content = bounds(activity.findViewById(android.R.id.content))
                assertTrue("Snackbar must fit horizontally", bar.left >= content.left && bar.right <= content.right)
                val text = snackbar.messageView
                assertTrue(text.lineCount > 1)
                assertEquals(0, text.layout.getEllipsisCount(text.lineCount - 1))
            }
            capture("long-message")
            val dismissed = CountDownLatch(1)
            scenario.onActivity { snackbar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(sb: Snackbar, event: Int) { dismissed.countDown() }
            }) }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(dismissed.await(5, TimeUnit.SECONDS))
            assertFalse(snackbar.isShownOrQueued)
            instrumentation.runOnMainSync { assertNull(feedback.show("Background result")) }
        }
    }

    private fun awaitPlacement(scenario: ActivityScenario<ScheduleComposeActivity>, snackbar: Snackbar) {
        val deadline = android.os.SystemClock.uptimeMillis() + 2000
        var placed = false
        while (!placed && android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                val bar = bounds(snackbar.view.findViewById(com.google.android.material.R.id.snackbar_content_layout))
                val anchor = bounds(activity.findViewById(R.id.schedule_compose_bottom_bar))
                placed = bar.height() > 0 && bar.bottom <= anchor.top
            }
            if (!placed) android.os.SystemClock.sleep(20)
        }
    }

    private fun bounds(view: View) = Rect().also { view.getGlobalVisibleRect(it) }

    private fun capture(name: String) {
        val dir = File(context.getExternalFilesDir(null), "snackbar-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
