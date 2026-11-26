package com.tjg.twidget.brief

import android.app.Instrumentation
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.data.TwidgetStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefCachedEntryInstrumentedTest {
    @Test fun cachedOnboardingKeepsItsMaterialAndMeasuredBriefStillShowsTweetReviews() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val main = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val settings = context.getSharedPreferences("twidget_brief_settings", Context.MODE_PRIVATE)
        val mainKeys = listOf("username", "onboarded", "refresh_on_launch", "share_history")
        val settingKeys = listOf("enabled", "onboarding_complete", "content_tweet_reviews", "content_regeneration_pending")
        val savedMain = mainKeys.associateWith { main.all[it] }
        val savedSettings = settingKeys.associateWith { settings.all[it] }
        val account = "bg_${System.nanoTime().toString().takeLast(8)}"
        val lock = BriefAiCoordinator::class.java.getDeclaredField("generationMutex").apply { isAccessible = true }.get(BriefAiCoordinator) as Mutex
        val monitor = Instrumentation.ActivityMonitor(TwidgetBriefActivity::class.java.name, null, false)
        var content: TwidgetBriefActivity? = null
        fun restore(prefs: android.content.SharedPreferences, saved: Map<String, Any?>) = prefs.edit().apply {
            saved.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } }
        }.commit()
        lock.lock() // A regression must fail this test, never make a real provider request.
        TwidgetStore.addAccount(context, account)
        try {
            main.edit().putString("username", account).putBoolean("onboarded", true)
                .putBoolean("refresh_on_launch", false).putBoolean("share_history", false).commit()
            settings.edit().putBoolean("enabled", true).putBoolean("onboarding_complete", false)
                .putBoolean("content_tweet_reviews", true).putBoolean("content_regeneration_pending", false).commit()
            val now = System.currentTimeMillis()
            val posts = JSONArray((0..1).map { index -> JSONObject().put("url", "https://x.com/$account/status/${100 + index}")
                .put("text", "Review fixture post $index").put("fullText", "Review fixture post $index")
                .put("views", 100).put("likes", 5).put("replies", 2).put("reposts", 1).put("quotes", 0)
                .put("engagements", 8).put("ts", now - 3_600_000).put("authorName", "Fixture").put("authorUserName", account) })
            context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE).edit()
                .putString("v7:${TwidgetStore.settings(context).dataSource}:$account", JSONObject().put("userName", account)
                    .put("followers", 100).put("postsAnalyzed", 2).put("windowDays", 7).put("cachedAt", now).put("recentPosts", posts).toString()).commit()
            val source = BriefEngine.rebuild(context, account, force = true, reuseNarrative = false)
            BriefStore.write(context, source.copy(headline = "Your saved Brief", subheading = "Your saved overview.", shortDescription = "Your saved overview.",
                summaryGenerated = true, summaryGeneratedAt = now, aiGeneratedAt = now,
                providerUsed = if (BriefSettingsStore.provider(context) == BriefProviderMode.CLOUD) BriefProviderUsed.CLOUD else BriefProviderUsed.LOCAL,
                cards = source.cards.map { it.copy(narrativeGenerated = true, narrativeGeneratedAt = now) }))
            instrumentation.addMonitor(monitor)
            ActivityScenario.launch<BriefOnboardingActivity>(BriefOnboardingActivity.intent(context, account)).use { intro ->
                val deadline = android.os.SystemClock.uptimeMillis() + 8_000
                while (BriefLaunchGeneration.current(account)?.isCompleted != true && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
                assertTrue("Explore reuses a completed overview without waiting for the locked provider", BriefLaunchGeneration.current(account)?.isCompleted == true)
                intro.onActivity { activity ->
                    val background = activity.findViewById<View>(R.id.brief_onboarding_root).background as BriefBlueBackground
                    assertEquals(0f, background.revealProgress, .001f)
                    activity.findViewById<View>(R.id.brief_onboarding_continue).performClick()
                }
                content = monitor.waitForActivityWithTimeout(8_000) as? TwidgetBriefActivity
                assertNotNull(content)
                val page = content!!
                val revealing = TwidgetBriefActivity::class.java.getDeclaredField("entranceInProgress").apply { isAccessible = true }
                val rendered = TwidgetBriefActivity::class.java.getDeclaredField("renderedSnapshot").apply { isAccessible = true }
                val readyBy = android.os.SystemClock.uptimeMillis() + 10_000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < readyBy) {
                    instrumentation.runOnMainSync { ready = rendered.get(page) != null && !revealing.getBoolean(page) }
                    if (!ready) Thread.sleep(30)
                }
                assertTrue("Cached Brief opens without provider work", ready)
                val measured = BriefEngine.rebuild(context, account, force = true, reuseNarrative = false).copy(cards = emptyList())
                assertFalse(measured.hasGeneratedNarrative)
                assertEquals(BriefProviderUsed.TEMPLATE, measured.providerUsed)
                instrumentation.runOnMainSync {
                    TwidgetBriefActivity::class.java.getDeclaredMethod("render", BriefSnapshot::class.java, PostAnalytics::class.java)
                        .apply { isAccessible = true }.invoke(page, measured, AnalyticsClient.cached(context, account))
                    val root = page.findViewById<ViewGroup>(R.id.brief_cards)
                    assertNotNull("Review library remains visible without fresh AI prose", root.findViewById<View>(R.id.review_library_card))
                    assertTrue(root.walk().filterIsInstance<TextView>().any { it.text.toString().contains("Review fixture post 0") })
                    val background = page.findViewById<View>(R.id.brief_root).background as BriefBlueBackground
                    assertEquals(1f, background.revealProgress, .001f)
                }
            }
            instrumentation.runOnMainSync { content?.finish() }
            content = null
            ActivityScenario.launch<TwidgetBriefActivity>(TwidgetBriefActivity.contentIntent(context, account)).use { entry ->
                val animator = TwidgetBriefActivity::class.java.getDeclaredField("backgroundUnlockAnimator").apply { isAccessible = true }
                entry.onActivity { page ->
                    val background = page.findViewById<View>(R.id.brief_root).background as BriefBlueBackground
                    assertTrue("Normal entry starts a background reveal", background.revealProgress > 0f && background.revealProgress < 1f)
                    assertNotNull(animator.get(page))
                    // Finish deterministically before exercising a child-page-style pause/resume.
                    (animator.get(page) as android.animation.ValueAnimator).end()
                }
                entry.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                entry.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                entry.onActivity { page ->
                    assertEquals(1f, (page.findViewById<View>(R.id.brief_root).background as BriefBlueBackground).revealProgress, .001f)
                    assertNull("Returning to Brief does not replay the entry reveal", animator.get(page))
                }
            }
        } finally {
            BriefLaunchGeneration.current(account)?.cancel()
            instrumentation.runOnMainSync { content?.finish() }
            instrumentation.removeMonitor(monitor)
            restore(main, savedMain); restore(settings, savedSettings)
            TwidgetStore.removeAccount(context, account)
            lock.unlock()
        }
    }

    private fun View.walk(): Sequence<View> = sequence {
        yield(this@walk)
        val group = this@walk as? ViewGroup ?: return@sequence
        for (index in 0 until group.childCount) yieldAll(group.getChildAt(index).walk())
    }
}
