package com.tjg.twidget.brief

import android.animation.ValueAnimator
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefEntranceInstrumentedTest {
    @Test fun cachedOpeningsAndReturnsReplayTheRevealWithoutRegenerating() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val account = TwidgetStore.settings(context).username
        assumeTrue(account.isNotBlank())
        val cached = BriefEngine.rebuild(context, account)
        assumeTrue("This integration check needs an existing successful cached Brief", cached.hasGeneratedNarrative)
        assumeTrue(BriefRunCache.providerMatches(BriefSettingsStore.provider(context), cached.providerUsed))
        val onboarding = BriefSettingsStore.onboardingComplete(context)
        val enabled = BriefSettingsStore.enabled(context)
        try {
            // Keep foreground preparation from starting unrelated inference.
            BriefSettingsStore.setOnboardingComplete(context, false)
            repeat(2) {
                ActivityScenario.launch<TwidgetBriefActivity>(TwidgetBriefActivity.contentIntent(context, account)).use { scenario ->
                    val first = awaitReveal(scenario)
                    // The immediate cache validation must leave the original reveal running.
                    android.os.SystemClock.sleep(300)
                    scenario.onActivity { activity ->
                        assertSame(first, animation(activity))
                        assertTrue("An unchanged cache redraw must not cancel the reveal", first.isStarted)
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.brief_scroll).visibility)
                        assertEquals(cached.headline, activity.findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title).text.toString())
                    }
                    scenario.moveToState(Lifecycle.State.STARTED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    val second = awaitReveal(scenario, first)
                    assertNotSame("Returning to the same Brief must start a new reveal", first, second)
                    scenario.onActivity { activity ->
                        // Returning from a review/settings child can also rebind the cached content.
                        TwidgetBriefActivity::class.java.getDeclaredField("checkContentOnResume").apply {
                            isAccessible = true
                            setBoolean(activity, true)
                        }
                    }
                    scenario.moveToState(Lifecycle.State.STARTED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    assertNotSame(second, awaitReveal(scenario, second))
                }
            }
            assertEquals("Replaying entrance motion must not regenerate the Brief", cached.aiGeneratedAt,
                BriefStore.read(context, account)!!.aiGeneratedAt)
        } finally {
            BriefSettingsStore.setOnboardingComplete(context, onboarding)
            BriefSettingsStore.setEnabled(context, enabled)
        }
    }

    private fun awaitReveal(scenario: ActivityScenario<TwidgetBriefActivity>, previous: ValueAnimator? = null): ValueAnimator {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        var found: ValueAnimator? = null
        while (found == null && android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                animation(activity)?.takeIf { it !== previous && it.isStarted }?.let { found = it }
            }
            if (found == null) android.os.SystemClock.sleep(25)
        }
        return checkNotNull(found) { "The Brief heading reveal did not start" }
    }

    private fun animation(activity: TwidgetBriefActivity): ValueAnimator? =
        NowBriefSubtitleMotionView::class.java.getDeclaredField("animator").run {
            isAccessible = true
            get(activity.findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)) as? ValueAnimator
        }
}
