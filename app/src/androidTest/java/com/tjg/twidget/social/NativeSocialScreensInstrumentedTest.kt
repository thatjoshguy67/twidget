package com.tjg.twidget.social

import android.content.Intent
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefDebugScenario
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import com.tjg.twidget.ui.MetricChartView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSocialScreensInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun linkedProfilesRetainTheNativeDashboardAndBrief() {
        val before = SocialRepository(context).use { it.synchronizeLegacyFrom(context) }
        val settings = TwidgetStore.settings(context)
        val cards = TwidgetStore.dashboardCards(context)
        val debug = TwidgetStore.debugMenuUnlocked(context)
        val youtube = PlatformAccount.create(SocialPlatform.YOUTUBE, "UC-native-screen-fixture", "native-fixture", "YouTube")
        val existingX = before.accounts.firstOrNull { it.platform == SocialPlatform.X &&
            before.profileFor(it.id)?.accountIds?.none { id -> before.accountsById[id]?.platform == SocialPlatform.YOUTUBE } == true }
        val x = existingX ?: PlatformAccount.create(SocialPlatform.X, "native-screen-x", "native_fixture", "Linked profile")
        try {
            TwidgetStore.saveSettings(context, settings.copy(refreshOnLaunch = false))
            TwidgetStore.saveDashboardCards(context, listOf("milestone", "followers", "top_followers", "following", "posts", "likes"))
            TwidgetStore.setDebugMenuUnlocked(context, true)
            val fixture = SocialRepository(context).use { repository ->
                repository.completeUpgradeIntroduction()
                if (existingX == null) repository.connect(SocialProfileResult.Success(x, emptyList()))
                val now = System.currentTimeMillis()
                repository.connect(SocialProfileResult.Success(youtube, (0..6).flatMap { day ->
                    listOf(MetricObservation(youtube.id, SocialMetric.SUBSCRIBERS, 167L + day, now - (6 - day) * 86400000L, "fixture", MetricPrecision.ROUNDED),
                        MetricObservation(youtube.id, SocialMetric.VIDEOS, 12, now - (6 - day) * 86400000L, "fixture"))
                }))
                repository.edit { catalog ->
                    val target = catalog.profileFor(x.id)!!.id
                    SocialProfilePolicy.link(catalog, target, setOf(catalog.profileFor(youtube.id)!!.id)).copy(defaultProfileId = target)
                }
            }
            val id = fixture.defaultProfileId!!
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<GridLayout>(R.id.dashboard_content)?.findViewWithTag<View>("metric:${youtube.id}:subscribers") != null
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
                    assertTrue(activity.findViewById<View>(R.id.main_account_page).isShown)
                    assertNotNull(grid.findViewById<View>(R.id.brief_dashboard_title))
                    assertNotNull(grid.findViewById<MetricChartView>(R.id.followers_chart))
                    val yt = grid.findViewWithTag<View>("metric:${youtube.id}:subscribers")
                    assertEquals("≈ 173", yt.findViewById<TextView>(R.id.followers_value).text.toString())
                    assertTrue(grid.childCount >= 6)
                    assertNull(activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("social_dashboard"))
                }
                capture("dashboard-native")
                scenario.onActivity { activity ->
                    val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
                    val yt = grid.findViewWithTag<View>("metric:${youtube.id}:subscribers")
                    activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.dashboard_scroll).scrollTo(0, yt.top)
                }
                capture("dashboard-platforms")
            }
            val briefIntent = TwidgetBriefActivity.debugIntent(context, x.handle, BriefDebugScenario.GROWTH)
            ActivityScenario.launch<TwidgetBriefActivity>(briefIntent).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity { activity ->
                        ready = activity.findViewById<View>(R.id.brief_scroll)?.isShown == true &&
                            (activity.findViewById<LinearLayout>(R.id.brief_cards)?.childCount ?: 0) > 0
                    }
                    ready
                }
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(R.id.brief_root).background)
                    assertNotNull(activity.findViewById<View>(R.id.brief_summary_title))
                    val body = activity.findViewById<LinearLayout>(R.id.brief_cards)
                    val text = labels(body)
                    assertFalse(text.any { it.startsWith("Twitter/X · @") || it.startsWith("YouTube · @") })
                    assertFalse(text.any { it.contains(context.getString(R.string.social_all_audience)) })
                    // Rounded subscriber totals with no exact change are dashboard evidence, not a Brief item.
                    assertFalse(text.any { it == "≈ 173" })
                    fun hasChart(view: View): Boolean = view is MetricChartView ||
                        view is android.view.ViewGroup && (0 until view.childCount).any { hasChart(view.getChildAt(it)) }
                    assertTrue(hasChart(body))
                    assertEquals(fixture.profiles.first { it.id == id }.displayName(fixture.accountsById),
                        activity.findViewById<TextView>(R.id.brief_footer_account).text.toString())
                }
                capture("brief-native")
            }
        } finally {
            SocialRepository(context).use { it.edit { before } }
            TwidgetStore.saveSettings(context, settings)
            TwidgetStore.saveDashboardCards(context, cards)
            TwidgetStore.setDebugMenuUnlocked(context, debug)
        }
    }

    private fun labels(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is android.view.ViewGroup) (0 until view.childCount).forEach { addAll(labels(view.getChildAt(it))) }
    }
    private fun await(ready: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < until) {
            instrumentation.waitForIdleSync()
            if (ready()) return
            Thread.sleep(100)
        }
        fail("Native social screen did not finish loading")
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureOnboarding") != "true") return
        instrumentation.waitForIdleSync(); Thread.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
