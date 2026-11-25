package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.TweetPopOverLauncher
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefEntranceInstrumentedTest {
    @Test fun openingAnimatesButPopoverReturnsKeepPresentation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val keys = listOf("username", "accounts", "onboarded", "refresh_on_launch", "share_history", "debug_menu_unlocked")
        val previous = prefs.all.filterKeys { it in keys }
        val account = "brief_popover_return_test"
        prefs.edit().putString("username", account).putString("accounts", "[\"$account\"]")
            .putBoolean("onboarded", true).putBoolean("refresh_on_launch", false)
            .putBoolean("share_history", false).putBoolean("debug_menu_unlocked", true).commit()
        val onboarding = BriefSettingsStore.onboardingComplete(context)
        val enabled = BriefSettingsStore.enabled(context)
        val opened = mutableListOf<Intent>()
        var rejectX = false
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                if (rejectX && intent.`package` == "com.twitter.android") throw ActivityNotFoundException("Fixture: X unavailable")
                opened += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        fun settle() { android.os.SystemClock.sleep(350); instrumentation.waitForIdleSync() }
        try {
            BriefSettingsStore.setOnboardingComplete(context, false)
            ActivityScenario.launch<TwidgetBriefActivity>(TwidgetBriefActivity.debugIntent(context, account, BriefDebugScenario.GROWTH)).use { scenario ->
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                var first: ValueAnimator? = null
                while (first == null && android.os.SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { first = animation(it) }
                    if (first == null) android.os.SystemClock.sleep(30)
                }
                assertNotNull("Initial opening still has an entrance animation", first)
                lateinit var refreshed: BriefSnapshot
                scenario.onActivity { activity ->
                    val field = TwidgetBriefActivity::class.java.getDeclaredField("renderedSnapshot").apply { isAccessible = true }
                    val original = field.get(activity) as BriefSnapshot
                    val sections = activity.findViewById<android.view.ViewGroup>(R.id.brief_cards)
                    val originalCard = sections.getChildAt(0)
                    val render = TwidgetBriefActivity::class.java.getDeclaredMethod("render", BriefSnapshot::class.java, PostAnalytics::class.java)
                        .apply { isAccessible = true }
                    // Refresh timestamps and per-card content can change during a cached reveal.
                    val intermediate = original.copy(generatedAt = original.generatedAt + 1)
                    refreshed = intermediate.copy(generatedAt = intermediate.generatedAt + 1,
                        cards = intermediate.cards.mapIndexed { index, card ->
                            if (index == 0) card.copy(body = "Updated card evidence.") else card
                        })
                    render.invoke(activity, intermediate, null)
                    render.invoke(activity, refreshed, null)
                    assertSame("Background updates must not cancel heading motion", first, animation(activity))
                    assertTrue(first!!.isStarted)
                    assertSame("Revealing cards must stay mounted", originalCard, sections.getChildAt(0))
                    assertEquals("Latest data waits until the reveal finishes", original, field.get(activity))
                }
                scenario.onActivity { first!!.end() }
                val finishDeadline = android.os.SystemClock.uptimeMillis() + 10_000
                var revealing = true
                while (revealing && android.os.SystemClock.uptimeMillis() < finishDeadline) {
                    scenario.onActivity { activity ->
                        revealing = TwidgetBriefActivity::class.java.getDeclaredField("entranceInProgress").run {
                            isAccessible = true; getBoolean(activity)
                        }
                    }
                    if (revealing) android.os.SystemClock.sleep(30)
                }
                assertFalse("The reveal must settle and release pending data", revealing)
                scenario.onActivity { activity ->
                    assertEquals(refreshed, TwidgetBriefActivity::class.java.getDeclaredField("renderedSnapshot").run {
                        isAccessible = true; get(activity)
                    })
                    assertTrue(activity.findViewById<android.view.ViewGroup>(R.id.brief_cards).getChildAt(0).alpha == 1f)
                }
                settle()
                instrumentation.addMonitor(monitor)
                scenario.onActivity { activity ->
                    val url = "https://x.com/$account/status/123456789"
                    val wrapped = ContextThemeWrapper(activity, R.style.Theme_Twidget)
                    BriefReviewTweetCard.openTweet(wrapped, url)
                    assertEquals(url, opened.last().dataString)
                    assertEquals("com.twitter.android", opened.last().`package`)
                    assertEquals(0, opened.last().flags and Intent.FLAG_ACTIVITY_NEW_TASK)
                    assertTrue(opened.last().getBooleanExtra("com.x.deeplink.EXTRA_INTERNAL_DEEPLINK", false))
                    assertTrue("A nested popover remains eligible at narrow window widths", TweetPopOverLauncher.supportsPopOver(360, true))
                    activity.intent.putExtra(FoldablePopOverActivity.EXTRA_POPOVER, true)
                    try {
                        BriefReviewTweetCard.openTweet(wrapped, url)
                        assertEquals(url, opened.last().dataString)
                        rejectX = true
                        BriefReviewTweetCard.openTweet(wrapped, url)
                        assertNull("Missing X falls back to normal link handling", opened.last().`package`)
                        assertEquals(url, opened.last().dataString)
                    } finally { activity.intent.removeExtra(FoldablePopOverActivity.EXTRA_POPOVER) }
                }
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                settle()
                scenario.onActivity { activity ->
                    assertSame("Returning must not create another entrance animation", first, animation(activity))
                    assertFalse(first!!.isStarted)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.brief_scroll).visibility)
                    TwidgetBriefActivity::class.java.getDeclaredField("checkContentOnResume").apply {
                        isAccessible = true; setBoolean(activity, true)
                    }
                }
                scenario.moveToState(Lifecycle.State.STARTED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                settle()
                scenario.onActivity { activity ->
                    assertFalse("An unchanged settings return must stay static", animation(activity)?.isStarted == true)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            BriefSettingsStore.setOnboardingComplete(context, onboarding)
            BriefSettingsStore.setEnabled(context, enabled)
            prefs.edit().apply {
                keys.forEach(::remove)
                previous.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                } }
            }.commit()
        }
    }

    private fun animation(activity: TwidgetBriefActivity): ValueAnimator? =
        NowBriefSubtitleMotionView::class.java.getDeclaredField("animator").run {
            isAccessible = true
            get(activity.findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)) as? ValueAnimator
        }
}
