package com.tjg.twidget.social

import android.content.Intent
import android.graphics.Rect
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.main.OnboardingActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialFlowInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun awaitReady(scenario: ActivityScenario<SocialOnboardingActivity>) {
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) {
            var ready = false
            scenario.onActivity { ready = !it.busy }
            if (ready) { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); return }
            Thread.sleep(50)
        }
        fail("Onboarding did not become ready")
    }
    @Test fun platformChooserUsesAllFivePlatformsAndVisibleFooter() {
        ActivityScenario.launch<SocialOnboardingActivity>(Intent(context, SocialOnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, true)).use { scenario ->
            awaitReady(scenario)
            scenario.onActivity { activity ->
                assertEquals(SocialOnboardingActivity.Step.PLATFORMS, activity.step)
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.social_fragment) as SocialOnboardingFragment
                SocialPlatform.entries.forEach { assertNotNull(fragment.requireView().findViewWithTag<android.view.View>(it.storageId)) }
                val footer = activity.findViewById<android.view.View>(R.id.social_next)
                val bounds = Rect()
                if (activity.catalog.accounts.isEmpty()) assertFalse(footer.getGlobalVisibleRect(bounds)) else {
                    assertTrue(footer.getGlobalVisibleRect(bounds))
                    assertTrue(bounds.height() >= (40 * activity.resources.displayMetrics.density).toInt())
                }
            }
        }
    }
    @Test fun accountEntrySurvivesActivityRecreation() {
        ActivityScenario.launch<SocialOnboardingActivity>(Intent(context, SocialOnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, true)).use { scenario ->
            awaitReady(scenario)
            scenario.onActivity { it.choose(SocialPlatform.BLUESKY); it.handle = "bsky.app" }
            scenario.recreate(); awaitReady(scenario)
            scenario.onActivity {
                assertEquals(SocialOnboardingActivity.Step.CONNECT, it.step)
                assertEquals(SocialPlatform.BLUESKY, it.platform)
                assertEquals("bsky.app", it.handle)
            }
        }
    }
    @Test fun onboardingDisplayKeepsAvatarAndNameSelectionsIndependent() {
        ActivityScenario.launch<SocialOnboardingActivity>(Intent(context, SocialOnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, true)).use { scenario ->
            awaitReady(scenario)
            val accounts = listOf(
                PlatformAccount.create(SocialPlatform.X, "sample-x", "example", "Example name"),
                PlatformAccount.create(SocialPlatform.INSTAGRAM, "sample-ig", "example.ig", "Instagram name"),
                PlatformAccount.create(SocialPlatform.GITHUB, "sample-gh", "example-github", "GitHub name"))
            var fixture = accounts.fold(SocialCatalog()) { catalog, account -> SocialProfilePolicy.add(catalog, account) }
            fun show(step: SocialOnboardingActivity.Step, name: String) {
                scenario.onActivity { it.step = step; it.render() }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                if (InstrumentationRegistry.getArguments().getString("captureOnboarding") == "true") {
                    Thread.sleep(1000)
                    val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    val file = java.io.File(context.getExternalFilesDir(null), "onboarding-$name.png")
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            scenario.onActivity { it.catalog = SocialCatalog() }
            show(SocialOnboardingActivity.Step.WELCOME, "welcome")
            show(SocialOnboardingActivity.Step.PLATFORMS, "platforms")
            scenario.onActivity { it.platform = SocialPlatform.X; it.handle = "" }
            show(SocialOnboardingActivity.Step.CONNECT, "handle")
            scenario.onActivity { it.platform = SocialPlatform.INSTAGRAM }
            show(SocialOnboardingActivity.Step.CONNECT, "signin")
            scenario.onActivity { it.catalog = fixture; it.selected.addAll(fixture.profiles.map { p -> p.id }) }
            show(SocialOnboardingActivity.Step.PLATFORMS, "connected")
            show(SocialOnboardingActivity.Step.LINK, "link")
            fixture = SocialProfilePolicy.link(fixture, fixture.profiles.first().id, fixture.profiles.drop(1).map { it.id }.toSet())
            scenario.onActivity {
                it.catalog = fixture; it.editingProfile = fixture.profiles.first().id
                it.nameSource = accounts.first().id; it.avatarSource = accounts.first().id
            }
            show(SocialOnboardingActivity.Step.DISPLAY, "display")
            scenario.onActivity {
                val view = it.supportFragmentManager.findFragmentById(R.id.social_fragment)!!.requireView()
                view.findViewWithTag<android.view.View>("avatar:${accounts[1].id}").performClick()
                view.findViewWithTag<android.view.View>("name:${accounts[2].id}").performClick()
                assertEquals(accounts[1].id, it.avatarSource)
                assertEquals(accounts[2].id, it.nameSource)
            }
            show(SocialOnboardingActivity.Step.READY, "ready")
            show(SocialOnboardingActivity.Step.WIDGET, "widget")
            show(SocialOnboardingActivity.Step.DONE, "done")
            scenario.recreate(); awaitReady(scenario)
            scenario.onActivity {
                assertEquals(accounts[1].id, it.avatarSource)
                assertEquals(accounts[2].id, it.nameSource)
            }
        }
    }
    @Test fun readyOptionsOnlyUseTheProfileBeingConfigured() {
        ActivityScenario.launch<SocialOnboardingActivity>(Intent(context, SocialOnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, true)).use { scenario ->
            awaitReady(scenario)
            val accounts = listOf(
                PlatformAccount.create(SocialPlatform.X, "other-x", "unrelated", "Unrelated"),
                PlatformAccount.create(SocialPlatform.INSTAGRAM, "test-ig", "instagram", "Instagram"),
                PlatformAccount.create(SocialPlatform.GITHUB, "test-gh", "github", "GitHub"),
                PlatformAccount.create(SocialPlatform.BLUESKY, "test-bsky", "bsky.test", "Bluesky"),
                PlatformAccount.create(SocialPlatform.X, "test-x", "configured", "Configured"))
            var fixture = accounts.fold(SocialCatalog()) { catalog, account -> SocialProfilePolicy.add(catalog, account) }
            val instagram = fixture.profileFor(accounts[1].id)!!.id
            fixture = SocialProfilePolicy.link(fixture, instagram, setOf(fixture.profileFor(accounts[2].id)!!.id))
            fun check(profileId: String, buffer: Boolean, xImport: Boolean) {
                scenario.onActivity { activity ->
                    activity.catalog = fixture; activity.editingProfile = profileId
                    activity.step = SocialOnboardingActivity.Step.READY; activity.render()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val labels = mutableListOf<String>()
                    fun collect(view: android.view.View) {
                        if (view is TextView) labels += view.text.toString()
                        if (view is android.view.ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
                    }
                    collect(activity.supportFragmentManager.findFragmentById(R.id.social_fragment)!!.requireView())
                    assertEquals(buffer, activity.getString(R.string.social_link_buffer) in labels)
                    assertEquals(xImport, activity.getString(R.string.import_x_analytics) in labels)
                    assertEquals(buffer || xImport, activity.getString(R.string.social_optional) in labels)
                    assertFalse("Unrelated saved X account leaked into this profile", "@unrelated" in labels)
                    if (xImport) assertTrue("@configured" in labels)
                }
            }
            check(instagram, buffer = false, xImport = false)
            check(fixture.profileFor(accounts[3].id)!!.id, buffer = true, xImport = false)
            check(fixture.profileFor(accounts[4].id)!!.id, buffer = true, xImport = true)
            fixture = SocialProfilePolicy.link(fixture, instagram, setOf(fixture.profileFor(accounts[4].id)!!.id))
            check(instagram, buffer = true, xImport = true)
        }
    }
    @Test fun linkedDashboardSeparatesPlatformsAndDoesNotDisplayMissingCountsAsZero() {
        ActivityScenario.launch<SocialOnboardingActivity>(Intent(context, SocialOnboardingActivity::class.java)
            .putExtra(OnboardingActivity.EXTRA_ADD_ACCOUNT, true)).use { scenario ->
            awaitReady(scenario)
            scenario.onActivity { activity ->
                val one = PlatformAccount.create(SocialPlatform.BLUESKY, "did:plc:test", "one.test", "One")
                val two = PlatformAccount.create(SocialPlatform.YOUTUBE, "UCtest", "@two", "Two")
                val view = android.widget.LinearLayout(activity)
                val now = System.currentTimeMillis()
                val observations = listOf(MetricObservation(one.id, SocialMetric.FOLLOWERS, 42, now, "test"),
                    MetricObservation(two.id, SocialMetric.SUBSCRIBERS, null, now, "test", MetricPrecision.ROUNDED))
                val bsky = SocialMetricCardFactory.create(activity, one, SocialMetric.FOLLOWERS, observations)
                val youtube = SocialMetricCardFactory.create(activity, two, SocialMetric.SUBSCRIBERS, observations)
                view.addView(bsky); view.addView(youtube)
                assertNotNull(bsky.findViewById<com.tjg.twidget.ui.MetricChartView>(R.id.followers_chart))
                assertNotNull(youtube.findViewById<android.widget.ImageView>(R.id.metric_platform_icon).drawable)
                assertEquals("42", bsky.findViewById<TextView>(R.id.followers_value).text.toString())
                assertEquals(activity.getString(R.string.social_unavailable), youtube.findViewById<TextView>(R.id.followers_value).text.toString())
                val labels = mutableListOf<String>()
                fun collect(node: android.view.View) {
                    if (node is TextView) labels += node.text.toString()
                    if (node is android.view.ViewGroup) (0 until node.childCount).forEach { collect(node.getChildAt(it)) }
                }
                collect(view)
                assertTrue(labels.any { it.contains("Bluesky") })
                assertTrue(labels.any { it.contains("YouTube") })
                assertTrue(labels.any { it.contains(activity.getString(R.string.social_unavailable)) })
                assertFalse(labels.any { it == "Subscribers  0" })
            }
        }
    }
}
