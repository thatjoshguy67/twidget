package com.tjg.twidget.settings

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.preference.Preference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefContentCategory
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefVisuals
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BriefContentOptionsInstrumentedTest {
    @Test fun eachContentCategoryAppearsExactlyOnce() {
        ActivityScenario.launch(BriefContentSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as BriefContentSettingsPreferenceFragment
                val screen = fragment.preferenceScreen
                val keys = (0 until screen.preferenceCount).mapNotNull { screen.getPreference(it).key }
                assertEquals("No repeated preference keys", keys.distinct().size, keys.size)
                for (category in listOf(BriefContentCategory.CONVERSATIONS, BriefContentCategory.CONTENT_PATTERNS,
                    BriefContentCategory.AUDIENCE_MOMENTUM, BriefContentCategory.WEEKLY_RECAP,
                    BriefContentCategory.TOPICS, BriefContentCategory.EXPERIMENTS)) assertEquals(category.storageId, 1,
                    keys.count { it == "brief_content_${category.storageId}" })
            }
        }
    }

    @Test fun insightOptionsKeepLegacyOrderAndExplainWithoutChangingSwitches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacy = listOf("tweet_reviews", "top_tweet", "worst_tweet", "posting_guidance", "followers",
            "top_followers", "tweet_activity", "account_goals").map { "brief_content_$it" }
        val newOptions = linkedMapOf(
            BriefContentCategory.CONVERSATIONS to R.string.brief_content_conversations_explainer,
            BriefContentCategory.CONTENT_PATTERNS to R.string.brief_content_patterns_explainer,
            BriefContentCategory.TOPICS to R.string.brief_content_topics_explainer,
            BriefContentCategory.EXPERIMENTS to R.string.brief_content_experiments_explainer,
            BriefContentCategory.AUDIENCE_MOMENTUM to R.string.brief_content_momentum_explainer,
            BriefContentCategory.WEEKLY_RECAP to R.string.brief_content_recap_explainer)
        val before = newOptions.keys.associateWith { BriefSettingsStore.contentEnabled(context, it) }
        ActivityScenario.launch(BriefContentSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                    as BriefContentSettingsPreferenceFragment
                val screen = fragment.preferenceScreen
                val keys = (0 until screen.preferenceCount).mapNotNull { screen.getPreference(it).key }
                assertEquals(legacy, keys.filter { it in legacy })
                assertTrue(keys.indexOf("brief_content_conversations") < keys.indexOf("brief_content_posting_guidance"))
                assertTrue(keys.indexOf("brief_content_experiments") > keys.indexOf("brief_content_posting_guidance"))
                assertTrue(keys.indexOf("brief_content_audience_momentum") in
                    (keys.indexOf("brief_content_followers") + 1) until keys.indexOf("brief_content_top_followers"))
                assertTrue(keys.indexOf("brief_content_weekly_recap") > keys.indexOf("brief_content_tweet_activity"))
                assertEquals(6, newOptions.keys.map(BriefVisuals::categoryIcon).distinct().size)
                for ((category, message) in newOptions) {
                    val preference = fragment.findPreference<Preference>("brief_content_${category.storageId}")!!
                    assertNotNull(preference.icon)
                    preference.performClick()
                    val expected = activity.getString(message)
                    assertTrue("The option must explain its measured data", WindowInspector.getGlobalWindowViews()
                        .any { root -> texts(root).any { it == expected } })
                    WindowInspector.getGlobalWindowViews().firstNotNullOf { it.findViewById<View>(android.R.id.button1) }.performClick()
                    assertEquals(before[category], BriefSettingsStore.contentEnabled(context, category))
                }
            }
        }
    }

    private fun texts(view: View): Sequence<String> = sequence {
        if (view is TextView) yield(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(texts(view.getChildAt(i)))
    }
}
