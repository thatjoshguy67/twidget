package com.tjg.twidget.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefOpenAi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsOrganizationInstrumentedTest {
    private fun save(activity: android.app.Activity, name: String) {
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(activity.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun briefLinksToProvidersInDataAndSources() {
        ActivityScenario.launch(BriefSettingsActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as BriefSettingsPreferenceFragment
                assertNotNull(fragment.findPreference<androidx.preference.Preference>("brief_manage_cloud_providers"))
                assertNull(fragment.findPreference<androidx.preference.Preference>("brief_cloud_service"))
                val screen = fragment.preferenceScreen
                val keys = (0 until screen.preferenceCount).map { screen.getPreference(it).key }
                assertEquals(keys.indexOf("brief_refresh_on_home") + 1, keys.indexOf("brief_content_pref"))
                assertEquals(keys.indexOf("brief_provider_pref") + 1, keys.indexOf("brief_manage_cloud_providers"))
                save(activity, "brief-settings-organized.png")
            }
        }
    }
    @Test fun dataShowsRelevantCloudCredentialsOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = BriefOpenAi.method(context)
        try {
            for (method in listOf("chatgpt", "key", "gemini")) {
                BriefOpenAi.prefs(context).edit().putString("method", method).commit()
                ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, SettingsPage.DATA)).use { scenario ->
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { activity ->
                        val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as SettingsCategoryPreferenceFragment
                        val choice = requireNotNull(fragment.findPreference<androidx.preference.ListPreference>("brief_cloud_service"))
                        assertEquals(method, choice.value)
                        assertNotNull(fragment.findPreference<androidx.preference.Preference>("data_source_pref"))
                        if (method == "gemini") assertNotNull(fragment.findPreference<androidx.preference.Preference>("brief_cloud_api_key_pref"))
                        if (method == "chatgpt") assertNull(fragment.findPreference<androidx.preference.Preference>("brief_cloud_api_key_pref"))
                        save(activity, "data-sources-$method.png")
                    }
                }
            }
        } finally { BriefOpenAi.prefs(context).edit().putString("method", original).commit() }
    }
}
