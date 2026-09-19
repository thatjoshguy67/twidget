package com.tjg.twidget.social

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.settings.BriefContentSettingsActivity
import com.tjg.twidget.settings.SettingsCategoryActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialRefinementsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(action: (SocialCatalog, SocialProfile, PlatformAccount, PlatformAccount) -> Unit) {
        val before = SocialRepository(context).use { it.synchronizeLegacyFrom(context) }
        val github = PlatformAccount.create(SocialPlatform.GITHUB, "refinements-github", "fixture-github", "GitHub name")
        val instagram = PlatformAccount.create(SocialPlatform.INSTAGRAM, "refinements-instagram", "fixture-instagram", "Instagram name")
        try {
            val fixture = SocialRepository(context).use { repo ->
                val now = System.currentTimeMillis()
                repo.connect(SocialProfileResult.Success(github, listOf(
                    MetricObservation(github.id, SocialMetric.STARS, 0, now - 86400000L, "fixture"),
                    MetricObservation(github.id, SocialMetric.FORKS, 0, now - 86400000L, "fixture"),
                    MetricObservation(github.id, SocialMetric.STARS, 12, now, "fixture"),
                    MetricObservation(github.id, SocialMetric.FORKS, 3, now, "fixture"))))
                repo.connect(SocialProfileResult.Success(instagram, emptyList()))
                repo.edit {
                    val id = it.profileFor(github.id)!!.id
                    SocialProfilePolicy.link(it, id, setOf(it.profileFor(instagram.id)!!.id)).copy(defaultProfileId = id)
                }
            }
            action(fixture, fixture.profiles.first { it.id == fixture.defaultProfileId }, github, instagram)
        } finally { SocialRepository(context).use { it.edit { before } } }
    }

    @Test fun editorKeepsDraftAcrossRecreationAndOnlyCommitsOnSave() = fixture { _, profile, github, instagram ->
        val intent = Intent(context, SocialOnboardingActivity::class.java)
            .putExtra(SocialOnboardingActivity.EXTRA_EDIT_PROFILE, profile.id)
        ActivityScenario.launch<SocialOnboardingActivity>(intent).use { scenario ->
            ready(scenario)
            scenario.onActivity { activity ->
                assertEquals(context.getString(R.string.save), activity.findViewById<TextView>(R.id.social_next).text)
                activity.findViewById<View>(R.id.social_fragment).findViewWithTag<View>("avatar:${instagram.id}").performClick()
                activity.findViewById<View>(R.id.social_fragment).findViewWithTag<View>("name:${instagram.id}").performClick()
                assertEquals(github.id, SocialRepository(context).use { it.catalog() }.profiles.first { it.id == profile.id }.nameAccountId)
            }
            scenario.recreate(); ready(scenario)
            capture("profile-editor")
            scenario.onActivity {
                assertEquals(instagram.id, it.avatarSource); assertEquals(instagram.id, it.nameSource)
                it.findViewById<View>(R.id.social_back).performClick()
            }
        }
        assertEquals(profile, SocialRepository(context).use { it.catalog() }.profiles.first { it.id == profile.id })
        ActivityScenario.launch<SocialOnboardingActivity>(intent).use { scenario ->
            ready(scenario)
            scenario.onActivity {
                it.avatarSource = instagram.id; it.nameSource = instagram.id
                it.findViewById<View>(R.id.social_next).performClick()
            }
            await { SocialRepository(context).use { it.catalog() }.profiles.first { it.id == profile.id }.nameAccountId == instagram.id }
        }
        val saved = SocialRepository(context).use { it.catalog() }.profiles.first { it.id == profile.id }
        assertEquals(instagram.id, saved.avatarAccountId); assertEquals(instagram.id, saved.nameAccountId)
        assertNull(saved.customDisplayName)
        assertEquals(profile.accountIds, saved.accountIds)
    }

    @Test fun contentSwitchesFilterStarsAndForksIndependently() = fixture { _, profile, github, _ ->
        val prefs = context.getSharedPreferences("social_brief_content", 0)
        val keys = listOf("github", "github:stars", "github:forks")
        val before = keys.associateWith { key -> if (prefs.contains(key)) prefs.getBoolean(key, true) else null }
        try {
            ProfileBriefEngine.setEnabled(context, "github", true)
            ProfileBriefEngine.setMetricEnabled(context, SocialPlatform.GITHUB, SocialMetric.STARS, true)
            ProfileBriefEngine.setMetricEnabled(context, SocialPlatform.GITHUB, SocialMetric.FORKS, true)
            ActivityScenario.launch<BriefContentSettingsActivity>(Intent(context, BriefContentSettingsActivity::class.java)).use { scenario ->
                await {
                    var loaded = false
                    scenario.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as? PreferenceFragmentCompat
                        loaded = fragment?.findPreference<Preference>("social_brief_github_stars") != null && fragment.listView.adapter != null
                    }
                    loaded
                }
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat
                    val stars = fragment.findPreference<SwitchPreferenceCompat>("social_brief_github_stars")!!
                    assertTrue(stars.isChecked)
                    stars.performClick()
                    assertFalse(stars.isChecked)
                    assertTrue(fragment.findPreference<SwitchPreferenceCompat>("social_brief_github_forks")!!.isChecked)
                    fragment.listView.scrollToPosition(fragment.listView.adapter!!.itemCount - 1)
                }
                instrumentation.waitForIdleSync()
                capture("brief-platform-content")
            }
            val brief = ProfileBriefEngine.rebuild(context, profile.id)
            assertFalse(brief.cards.any { it.id == "${github.id}:stars" })
            assertTrue(brief.cards.any { it.id == "${github.id}:forks" })
        } finally {
            prefs.edit().apply { before.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) } }.commit()
        }
    }

    @Test fun profileSettingsExposeAccountActionsAndTheRealEditor() = fixture { _, profile, _, instagram ->
        ActivityScenario.launch<SettingsCategoryActivity>(Intent(context, SettingsCategoryActivity::class.java).putExtra("settings_page", "ACCOUNTS")).use { scenario ->
            await {
                var ready = false
                scenario.onActivity { activity ->
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as? PreferenceFragmentCompat
                    ready = fragment?.findPreference<Preference>("account:${instagram.id}") != null
                }
                ready
            }
            capture("profiles-accounts")
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat
                assertNotNull(fragment.findPreference<Preference>("profile:${profile.id}"))
                fragment.findPreference<Preference>("account:${instagram.id}")!!.performClick()
            }
            instrumentation.waitForIdleSync()
            capture("account-actions")
        }
    }

    private fun ready(scenario: ActivityScenario<SocialOnboardingActivity>) = await {
        var ready = false
        scenario.onActivity { ready = !it.busy && it.catalog.profiles.isNotEmpty() }
        ready
    }
    private fun await(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (check()) return
            Thread.sleep(100)
        }
        fail("Screen did not become ready")
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureOnboarding") != "true") return
        instrumentation.waitForIdleSync(); Thread.sleep(500)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
