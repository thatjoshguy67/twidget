package com.tjg.twidget.social

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditablePlatformCardsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun allPlatformCardsCanBeReorderedRemovedAndAddedAcrossRecreation() {
        val before = SocialRepository(context).use { it.synchronizeLegacyFrom(context) }
        val settings = TwidgetStore.settings(context)
        val prefs = context.getSharedPreferences("profile_dashboard_layouts", 0)
        val saved = prefs.all.mapValues { it.value as String }
        val gh = PlatformAccount.create(SocialPlatform.GITHUB, "editable-gh", "fixture", "Creator")
        val yt = PlatformAccount.create(SocialPlatform.YOUTUBE, "editable-yt", "fixture", "Creator videos")
        val reposId = "metric:${gh.id}:repositories"
        val videoId = "video:${yt.id}"
        try {
            TwidgetStore.saveSettings(context, settings.copy(refreshOnLaunch = false))
            val now = System.currentTimeMillis()
            SocialRepository(context).use { repo ->
                repo.completeUpgradeIntroduction()
                repo.connect(SocialProfileResult.Success(gh, listOf(
                    MetricObservation(gh.id, SocialMetric.FOLLOWERS, 500, now, "fixture"),
                    MetricObservation(gh.id, SocialMetric.REPOSITORIES, 23, now, "fixture"))))
                repo.connect(SocialProfileResult.Success(yt, listOf(MetricObservation(yt.id, SocialMetric.SUBSCRIBERS, 173, now, "fixture", MetricPrecision.ROUNDED)),
                    YouTubeVideoSnapshot(now, listOf(YouTubeVideo("fixture", "A useful weekly upload", "", now - 3600000, 9600)))))
                repo.edit { catalog ->
                    val id = catalog.profileFor(gh.id)!!.id
                    SocialProfilePolicy.link(catalog, id, setOf(catalog.profileFor(yt.id)!!.id)).copy(defaultProfileId = id)
                }
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                ready(scenario) { grid -> grid.findViewWithTag<View>(reposId) != null }
                scenario.onActivity { activity ->
                    val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
                    val repos = grid.findViewWithTag<View>(reposId)
                    assertEquals("23", repos.findViewById<TextView>(R.id.followers_value).text.toString())
                    val videos = grid.findViewWithTag<View>("metric:${yt.id}:videos")
                    assertEquals("—", videos.findViewById<TextView>(R.id.followers_value).text.toString())
                    assertEquals(context.getString(R.string.social_unavailable), videos.findViewById<TextView>(R.id.stat_detail).text.toString())
                    assertEquals(View.VISIBLE, repos.findViewById<ImageView>(R.id.metric_platform_icon).visibility)
                    assertEquals((160 * activity.resources.displayMetrics.density).toInt(), repos.layoutParams.height)
                    assertTrue(labels(grid.findViewWithTag(videoId)).contains("A useful weekly upload"))
                    assertTrue(repos.performLongClick())
                }
                ready(scenario) { grid -> descendants(grid.findViewWithTag(reposId)).any { it is ImageButton } }
                scenario.onActivity { activity ->
                    val controller = activity.editModeController
                    controller.draggedCardId = reposId
                    controller.previewMoveDashboardCard(reposId, activity.dashboardBinder.dashboardCards().first())
                    controller.finishDashboardDrag(true)
                }
                ready(scenario) { it.getChildAt(0).tag == reposId }
                scenario.onActivity { activity ->
                    val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
                    assertEquals(activity.dashboardBinder.dashboardCards().size, grid.childCount)
                    // Every card, including combined audience and the video, has a remove control.
                    (0 until grid.childCount).forEach { i -> assertTrue(descendants(grid.getChildAt(i)).any { it is ImageButton && it.contentDescription == context.getString(R.string.delete) }) }
                    descendants(grid.findViewWithTag(videoId)).filterIsInstance<ImageButton>().single { it.contentDescription == context.getString(R.string.delete) }.performClick()
                }
                ready(scenario) { it.findViewWithTag<View>(videoId) == null }
                scenario.recreate()
                ready(scenario) { it.childCount > 0 && it.getChildAt(0).tag == reposId && it.findViewWithTag<View>(videoId) == null }
                scenario.onActivity { activity ->
                    activity.editModeController.showAddCardDialog()
                }
                var option: android.view.accessibility.AccessibilityNodeInfo? = null
                val dialogDeadline = System.currentTimeMillis() + 10000
                while (option == null && System.currentTimeMillis() < dialogDeadline) {
                    instrumentation.waitForIdleSync()
                    option = instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText(context.getString(R.string.youtube_best_video))?.firstOrNull()
                    if (option == null) Thread.sleep(100)
                }
                assertNotNull("Add cards dialog should list the removed video", option)
                val bounds = android.graphics.Rect().also { option!!.getBoundsInScreen(it) }
                val downTime = android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
                    val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                    assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                }
                ready(scenario) { it.findViewWithTag<View>(videoId) != null }
                scenario.onActivity { activity ->
                    val ids = activity.dashboardBinder.dashboardCards()
                    activity.dashboardBinder.saveDashboardCards(listOf(reposId, "metric:${yt.id}:videos", "combined_audience", videoId) + ids.filterNot {
                        it in setOf(reposId, "metric:${yt.id}:videos", "combined_audience", videoId)
                    })
                    activity.render()
                }
                ready(scenario) { it.childCount > 3 && it.getChildAt(2).tag == "combined_audience" }
                capture()
            }
        } finally {
            SocialRepository(context).use { it.edit { before } }
            TwidgetStore.saveSettings(context, settings)
            prefs.edit().clear().apply { saved.forEach { (k, v) -> putString(k, v) } }.commit()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun labels(view: View) = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
    private fun ready(scenario: ActivityScenario<MainActivity>, check: (GridLayout) -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            instrumentation.waitForIdleSync()
            var ready = false
            scenario.onActivity { activity -> activity.findViewById<GridLayout>(R.id.dashboard_content)?.let { ready = check(it) } }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Dashboard did not settle")
    }
    private fun capture() {
        if (InstrumentationRegistry.getArguments().getString("captureOnboarding") != "true") return
        instrumentation.waitForIdleSync(); Thread.sleep(500)
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            java.io.File(context.getExternalFilesDir(null), "editable-platform-cards.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
