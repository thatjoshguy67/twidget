package com.tjg.twidget.brief

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.analytics.PostMedia
import com.tjg.twidget.analytics.PostObservationHistory
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStore
import com.tjg.twidget.schedule.ScheduleThreadItem
import com.tjg.twidget.schedule.ScheduledPost
import com.tjg.twidget.settings.BriefContentSettingsActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceFragmentCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class BriefPostCoachInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun reviewsDiscloseCloudAndRespectLocalOnlyMode() = withSettings {
        ActivityScenario.launch(AboutActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(BriefProviderMode.LOCAL, BriefSettingsStore.provider(activity))
                var modeCalls = 0
                val screen = BriefReviewScreen(activity, BriefReviewCallbacks(
                    retry = {}, info = {}, addKey = {}, openPost = {}, changeMode = { modeCalls++ }))
                screen.render(BriefReviewState(phase = BriefReviewPhase.GENERATING))
                assertNotNull(findText(screen.view, activity.getString(R.string.coach_ai_cloud_note)))
                assertNull(findText(screen.view, activity.getString(R.string.coach_ai_local_note)))
                assertNull(findText(screen.view, activity.getString(R.string.coach_ai_auto_note)))
                screen.render(BriefReviewState(phase = BriefReviewPhase.FAILED))
                assertNotNull(findText(screen.view, activity.getString(R.string.review_error_local)))
                findText(screen.view, activity.getString(R.string.review_change_mode))!!.performClick()
                assertEquals(1, modeCalls)
                assertEquals(BriefProviderMode.LOCAL, BriefSettingsStore.provider(activity))
                screen.dispose()
            }
        }
    }

    @Test fun aSavedReviewInAnotherLanguageIsNotShownWhenRegenerationIsBlocked() = withSettings {
        withAccount { account, _ ->
            val now = System.currentTimeMillis()
            val post = posts(account, now).first().copy(media = emptyList())
            val analytics = AnalyticsClient.analyticsFromPosts(account, 100, 1, false, listOf(post), cachedAt = now)
            val current = BriefStrings.from(context)
            val other = BriefStrings.from(context, if (current.locale.language == "fr") "en" else "fr")
            val evidence = BriefReviewEvidence.create(post, analytics, emptyList())
            val input = BriefCoachAi.review(post, analytics, BriefPostCoach.review(post, analytics, other, now)!!, other, now)
                .copy(reviewEvidence = evidence, reviewCitations = evidence.citations(other))
            val subject = BriefReviewSubject(BriefReviewTarget(account, postUrl = post.url), input, post = post)
            val result = feedback(subject)
            assertNotNull(BriefPublishedReviewStore.save(context, subject, result, TwidgetStore.accountGeneration(context, account)))
            assertNull(AnalyticsClient.cached(context, account))
            ActivityScenario.launch<BriefTweetReviewActivity>(BriefTweetReviewActivity.intent(context, subject.target)).use { scenario ->
                waitForText(scenario, context.getString(R.string.review_error_local))
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.review_root)
                    assertNull(findText(root, result.feedback.possibleExplanation))
                    assertNull(findText(root, context.getString(R.string.review_next_steps_intro)))
                    val state = androidx.lifecycle.ViewModelProvider(activity)[BriefReviewSession::class.java].state.value
                    assertEquals(BriefReviewPhase.FAILED, state.phase)
                    assertEquals(current.languageTag, state.subject!!.input.language)
                    assertNull(state.result)
                }
            }
            assertEquals(other.languageTag, BriefPublishedReviewStore.read(context, subject.target)!!.subject.input.language)
        }
    }

    @Test fun reviewAndLibraryUseNativeFloatingControlsAndExpandedHeadingsOverTheBriefGradient() = withSettings {
        withAccount { account, _ ->
            val now = System.currentTimeMillis()
            val post = posts(account, now).first().copy(media = emptyList())
            val analytics = AnalyticsClient.analyticsFromPosts(account, 100, 1, false, listOf(post), cachedAt = now)
            val strings = BriefStrings.from(context)
            val evidence = BriefReviewEvidence.create(post, analytics, emptyList())
            val input = BriefCoachAi.review(post, analytics, BriefPostCoach.review(post, analytics, strings, now)!!, strings, now)
                .copy(reviewEvidence = evidence, reviewCitations = evidence.citations(strings))
            val target = BriefReviewTarget(account, postUrl = post.url)
            val subject = BriefReviewSubject(target, input, post = post)
            BriefPublishedReviewStore.save(context, subject, feedback(subject), TwidgetStore.accountGeneration(context, account))
            ActivityScenario.launch<BriefTweetReviewActivity>(BriefTweetReviewActivity.intent(context, target)).use { scenario ->
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                scenario.onActivity { activity ->
                    assertTrue("Reviews open with the heading collapsed", activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.review_app_bar).top < 0)
                    val toolbar = activity.findViewById<View>(R.id.review_toolbar)
                    val floating = toolbar.parent as com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
                    assertTrue("SESL owns item background transitions", floating.isEnabledToolbarItemBackgroundTransition())
                    assertFalse("Navigation stays visible while native item backgrounds transition", floating.enableScrollTransition)
                    assertSame(activity.findViewById<View>(R.id.review_scroll), floating.getNestedScrollView())
                }
                for ((mode, theme) in listOf(AppCompatDelegate.MODE_NIGHT_NO to "light", AppCompatDelegate.MODE_NIGHT_YES to "dark")) {
                    scenario.onActivity { it.delegate.localNightMode = mode }
                    waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                    scenario.onActivity { activity ->
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.review_toolbar)
                        assertTrue(toolbar.parent is com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout)
                        assertEquals(activity.getString(R.string.review_title), toolbar.title)
                        assertNotNull(toolbar.menu.findItem(R.id.review_refresh_action))
                        val collapsing = activity.findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.review_collapsing_toolbar)
                        assertEquals(toolbar.title, collapsing.title)
                        assertNotNull(activity.findViewById<View>(R.id.review_top_gradient).background)
                        val appBar = activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.review_app_bar)
                        assertTrue("The page needs a real expanded header", appBar.totalScrollRange > BriefReviewViews.dp(activity, 40))
                        appBar.setExpanded(true, false)
                    }
                    instrumentation.waitForIdleSync(); Thread.sleep(400)
                    saveScreen("brief-review-expanded-$theme.png")
                    scenario.onActivity { activity ->
                        val toolbar = activity.findViewById<View>(R.id.review_toolbar)
                        assertTrue("The native background policy remains enabled when expanded", (toolbar.parent as com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout).isEnabledToolbarItemBackgroundTransition())
                        val fade = activity.findViewById<View>(R.id.review_top_gradient)
                        val appBar = activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.review_app_bar)
                        assertTrue("The expanded heading clears the overflow fade", fade.y + fade.height <= appBar.paddingTop + 1)
                        assertEquals(0, activity.findViewById<View>(R.id.review_root).paddingBottom)
                    }
                    scenario.onActivity { it.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.review_app_bar).setExpanded(false, false) }
                    instrumentation.waitForIdleSync(); Thread.sleep(400)
                    saveScreen("brief-review-collapsed-$theme.png")
                    scenario.onActivity { activity ->
                        val toolbar = activity.findViewById<View>(R.id.review_toolbar)
                        assertTrue(toolbar.getGlobalVisibleRect(Rect()))
                        val floating = toolbar.parent as com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
                        assertTrue("SESL owns the collapsed state", floating.isEnabledToolbarItemBackgroundTransition())
                        assertEquals(0f, activity.findViewById<View>(R.id.review_top_gradient).translationY, 1f)
                        assertEquals(android.graphics.Color.TRANSPARENT, toolbar.backgroundTintList!!.defaultColor)
                        assertTrue("A downward pull expands the header before refreshing", activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.review_refresh).canChildScrollUp())
                        val content = activity.findViewById<View>(R.id.review_content)
                        assertTrue(content.width <= BriefReviewViews.dp(activity, 600))
                    }
                    scenario.onActivity { activity -> activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll).scrollTo(0, 180) }
                    instrumentation.waitForIdleSync(); Thread.sleep(350)
                    scenario.onActivity { activity ->
                        val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll)
                        val floating = activity.findViewById<View>(R.id.review_toolbar).parent as com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
                        assertTrue("The test must scroll actual content", scroll.scrollY > 0)
                        assertSame("The native container observes the actual scroll view", scroll, floating.getNestedScrollView())
                        assertTrue(floating.isEnabledToolbarItemBackgroundTransition())
                        assertTrue("The navigation toolbar stays visible after scrolling", floating.isShown && floating.alpha == 1f)
                    }
                    saveScreen("brief-review-scrolled-$theme.png")
                    scenario.onActivity { it.findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll).scrollTo(0, 0) }
                    instrumentation.waitForIdleSync(); Thread.sleep(350)
                    scenario.onActivity { activity ->
                        val floating = activity.findViewById<View>(R.id.review_toolbar).parent as com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
                        assertTrue("Returning to the top leaves the native policy in control", floating.isEnabledToolbarItemBackgroundTransition())
                    }
                    saveScreen("brief-review-returned-top-$theme.png")
                }
                scenario.onActivity { activity ->
                    var backs = 0
                    activity.onBackPressedDispatcher.addCallback(activity, object : androidx.activity.OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() { backs++ }
                    })
                    val toolbar = activity.findViewById<View>(R.id.review_toolbar)
                    toolbar.walk().filterIsInstance<android.widget.ImageButton>().first().performClick()
                    assertEquals(1, backs)
                }
            }
            ActivityScenario.launch<BriefReviewListActivity>(BriefReviewListActivity.intent(context, account)).use { scenario ->
                instrumentation.waitForIdleSync(); Thread.sleep(400)
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.review_toolbar)
                    assertTrue(toolbar.parent is com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout)
                    assertEquals(activity.getString(R.string.review_list_title), toolbar.title)
                    assertEquals(0, toolbar.menu.size())
                    assertEquals(toolbar.title, activity.findViewById<com.google.android.material.appbar.CollapsingToolbarLayout>(R.id.review_collapsing_toolbar).title)
                }
                saveScreen("brief-review-library-expanded.png")
            }
        }
    }





    @Test fun reviewPageShowsMeasuredCardsSourcesAndAnnotatedInfoDialog() = withSettings {
        val image = fixtureMedia()
        try {
        withAccount { account, cacheKey ->
            val now = System.currentTimeMillis()
            val posts = posts(account, now)
            val raw = JSONObject().put("userName", account).put("followers", 100).put("postsAnalyzed", 4)
                .put("windowDays", 7).put("cachedAt", now).put("recentPosts", JSONArray(posts.map(::json)))
            var observations = JSONArray()
            listOf(12L, 6L, 0L).forEachIndexed { index, hours ->
                val observed = posts.map { if (it.url == posts.first().url) it.copy(likes = listOf(1L, 8L, 12L)[index]) else it }
                observations = PostObservationHistory.record(observations,
                    AnalyticsClient.analyticsFromPosts(account, 100, 4, false, observed, cachedAt = now - hours * 3_600_000L), now)
            }
            raw.put(PostObservationHistory.FIELD, observations)
            context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE).edit().putString(cacheKey, raw.toString()).commit()
            val target = BriefReviewTarget(account, postUrl = posts.first().url)
            val subject = BriefReviewSubjects.load(context, target)!!
            val result = feedback(subject).copy(feedback = feedback(subject).feedback.copy(panelIds = listOf("interaction_breakdown", "history_comparison", "weekly_trend")))
            assertNotNull(BriefPublishedReviewStore.save(context, subject, result, TwidgetStore.accountGeneration(context, account)))
            ActivityScenario.launch<BriefTweetReviewActivity>(BriefTweetReviewActivity.intent(context, target)).use { scenario ->
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                instrumentation.waitForIdleSync(); Thread.sleep(650)
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.review_root)
                    assertNull(findText(root, context.getString(R.string.review_performance)))
                    assertNull(findText(root, context.getString(R.string.review_breakdown)))
                    assertNull(findText(root, context.getString(R.string.coach_possible_factors)))
                    assertNotNull(findText(root, context.getString(R.string.brief_metric_impressions)))
                    assertNotNull(findText(root, context.getString(R.string.review_replies)))
                    assertNotNull(findText(root, context.getString(R.string.brief_metric_retweets)))
                    assertNotNull(findText(root, context.getString(R.string.brief_metric_quote_tweets)))
                    assertNotNull(findText(root, context.getString(R.string.review_history_comparison)))
                    assertNull(findText(root, context.getString(R.string.coach_ai_basis)))
                    val reviewContent = activity.findViewById<ViewGroup>(R.id.review_content)
                    val originalCard = ((reviewContent.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0)
                    assertTrue("The review header opens X from the whole card", originalCard.isClickable)
                    assertTrue(originalCard.foreground is android.graphics.drawable.RippleDrawable)
                    assertNull("The review header has no separate Open tweet button", findText(originalCard, activity.getString(R.string.brief_action_open_tweet)))
                    val explanation = findText(root, "People outside the conversation may need a clearer introduction to attachment 1.")!!
                    assertNull("The summary sits directly on the page gradient", (explanation.parent as View).background)
                    val summaryRow = explanation.parent as LinearLayout
                    assertEquals(BriefReviewViews.dp(activity, 19), summaryRow.paddingStart)
                    val reviewBody = summaryRow.parent as ViewGroup
                    val summaryIndex = reviewBody.indexOfChild(summaryRow)
                    val grid = reviewBody.getChildAt(summaryIndex + 1) as ViewGroup
                    assertEquals("The core stats stay in a single grid", 3, grid.childCount)
                    assertEquals(BriefReviewViews.dp(activity, 20), (grid.layoutParams as LinearLayout.LayoutParams).topMargin)
                    for (index in 0..2) {
                        val row = grid.getChildAt(index) as ViewGroup
                        assertEquals("Core metrics form three consecutive pairs", 2, row.childCount)
                        if (index > 0) assertEquals(BriefReviewViews.dp(activity, 10), (row.layoutParams as LinearLayout.LayoutParams).topMargin)
                        assertEquals(BriefReviewViews.dp(activity, 10), (row.getChildAt(1).layoutParams as LinearLayout.LayoutParams).marginStart)
                        for (card in 0 until row.childCount) assertEquals(1, row.getChildAt(card).walk()
                            .count { it is com.tjg.twidget.ui.CardValueTextView })
                    }
                    assertFalse(root.walk().filterIsInstance<TextView>().any { Regex("\\b[mq]0\\b").containsMatchIn(it.text) })
                    assertNull(findText(root, result.feedback.suggestions.single().rewrite.orEmpty()))
                    assertNull("The rewrite uses a single introduction", findText(root, context.getString(R.string.coach_ai_rewrite)))
                    assertNull("Tweet Report must not offer the retired composer", findText(root, context.getString(R.string.review_open_composer)))
                    assertTrue(root.walk().count { it is com.tjg.twidget.ui.CardValueTextView } >= 8)
                    assertNotNull(findText(root, context.getString(R.string.review_trend_title)))
                    assertTrue(root.walk().any { it is com.tjg.twidget.ui.MetricChartView })
                    saveImage(activity.findViewById(R.id.review_content), "review-page-full.png")
                }
                saveScreen("review-page-top.png")
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.review_root)
                    root.walk().first { it.contentDescription == context.getString(R.string.review_stat_info_title) }.performClick()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val dialog = WindowInspector.getGlobalWindowViews().first { findText(it, context.getString(R.string.review_stat_info_body)) != null }
                    assertNotNull(findText(dialog, context.getString(R.string.review_stat_info_title)))
                    dialog.findViewById<View>(android.R.id.button1).performClick()
                }
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO }
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                instrumentation.waitForIdleSync(); Thread.sleep(650)
                scenario.onActivity { saveImage(it.findViewById(R.id.review_content), "review-page-refined-light-full.png") }
                saveScreen("review-page-light.png")
                scenario.onActivity { activity ->
                    val heading = findText(activity.findViewById(R.id.review_root), context.getString(R.string.review_next_steps_intro))!!
                    heading.requestRectangleOnScreen(Rect(0, 0, heading.width, heading.height), true)
                }
                instrumentation.waitForIdleSync()
                Thread.sleep(450)
                saveScreen("review-suggestions.png")
                scenario.onActivity { activity ->
                    activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll)
                        .scrollTo(0, activity.findViewById<View>(R.id.review_content).height)
                }
                instrumentation.waitForIdleSync(); Thread.sleep(350)
                saveScreen("review-page-refined-light-bottom.png")
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES }
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                instrumentation.waitForIdleSync(); Thread.sleep(650)
                scenario.onActivity { activity ->
                    activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll).scrollTo(0, 0)
                    saveImage(activity.findViewById(R.id.review_content), "review-page-refined-dark-full.png")
                }
                saveScreen("review-page-refined-dark.png")
                scenario.onActivity { activity ->
                    activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll)
                        .scrollTo(0, activity.findViewById<View>(R.id.review_content).height)
                }
                instrumentation.waitForIdleSync(); Thread.sleep(350)
                saveScreen("review-page-refined-dark-bottom.png")
            }
        }
        } finally { image.delete() }
    }

    @Test fun annotatedLoadingSettlesOnCompletionAndRefreshKeepsThePreviousReviewInLocalMode() = withSettings {
        val image = fixtureMedia()
        try { withAccount { account, cacheKey ->
            val now = System.currentTimeMillis()
            val posts = posts(account, now)
            context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE).edit().putString(cacheKey,
                JSONObject().put("userName", account).put("followers", 100).put("postsAnalyzed", posts.size)
                    .put("windowDays", 7).put("cachedAt", now).put("recentPosts", JSONArray(posts.map(::json))).toString()).commit()
            val target = BriefReviewTarget(account, postUrl = posts.first().url)
            val subject = BriefReviewSubjects.load(context, target)!!
            val result = feedback(subject)
            val saved = BriefPublishedReviewStore.save(context, subject, result, TwidgetStore.accountGeneration(context, account))!!
            ActivityScenario.launch<BriefTweetReviewActivity>(BriefTweetReviewActivity.intent(context, target)).use { scenario ->
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO }
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                scenario.onActivity { activity ->
                    val screen = BriefTweetReviewActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.get(activity) as BriefReviewScreen
                    screen.render(BriefReviewState(subject, BriefReviewPhase.GENERATING))
                }
                instrumentation.waitForIdleSync(); Thread.sleep(250)
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.review_root)
                    assertNotNull(findText(root, context.getString(R.string.review_generating)))
                    val preview = activity.findViewById<ViewGroup>(R.id.review_content).getChildAt(0) as ViewGroup
                    val card = (preview.getChildAt(0) as ViewGroup).getChildAt(0)
                    if (android.animation.ValueAnimator.areAnimatorsEnabled()) assertTrue(card.elevation > 0f)
                    assertTrue("Media should not dominate the review", card.height <= BriefReviewViews.dp(activity, 320))
                }
                saveScreen("review-annotated-generating-light.png")
                scenario.onActivity { activity ->
                    val screen = BriefTweetReviewActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.get(activity) as BriefReviewScreen
                    screen.render(BriefReviewState(subject, BriefReviewPhase.COMPLETE, result, saved.savedAt))
                }
                instrumentation.waitForIdleSync(); Thread.sleep(600)
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.review_root)
                    val preview = activity.findViewById<ViewGroup>(R.id.review_content).getChildAt(0) as ViewGroup
                    val card = (preview.getChildAt(0) as ViewGroup).getChildAt(0)
                    assertEquals(0f, card.elevation)
                    assertEquals(0f, card.rotation)
                    assertNull(findText(root, context.getString(R.string.coach_evidence)))
                    assertNull(findText(root, context.getString(R.string.coach_limits)))
                    root.walk().first { it.contentDescription == context.getString(R.string.coach_limits) }.performClick()
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val dialog = WindowInspector.getGlobalWindowViews().first { findText(it, context.getString(R.string.coach_limits)) != null }
                    assertNotNull(findText(dialog, context.getString(R.string.coach_limits)))
                    dialog.findViewById<View>(android.R.id.button1).performClick()
                }
                scenario.onActivity { activity ->
                    val icon = activity.findViewById<View>(R.id.review_root).walk().first {
                        it.contentDescription == context.getString(R.string.review_stat_info_title) }
                    assertTrue(icon.background is android.graphics.drawable.RippleDrawable)
                    icon.isPressed = true
                    icon.jumpDrawablesToCurrentState()
                }
                instrumentation.waitForIdleSync(); Thread.sleep(200)
                saveScreen("review-annotated-info-pressed-light.png")
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.review_root).walk().first {
                        it.contentDescription == context.getString(R.string.review_stat_info_title) }.isPressed = false
                }
                saveScreen("review-annotated-complete-light.png")
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES }
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                saveScreen("review-annotated-complete-dark.png")
                scenario.onActivity { activity ->
                    val screen = BriefTweetReviewActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.get(activity) as BriefReviewScreen
                    screen.render(BriefReviewState(subject, BriefReviewPhase.GENERATING))
                }
                instrumentation.waitForIdleSync(); Thread.sleep(250)
                saveScreen("review-annotated-generating-dark.png")
                scenario.onActivity { activity ->
                    val screen = BriefTweetReviewActivity::class.java.getDeclaredField("screen").apply { isAccessible = true }.get(activity) as BriefReviewScreen
                    screen.render(BriefReviewState(subject, BriefReviewPhase.COMPLETE, result, saved.savedAt))
                }
                instrumentation.waitForIdleSync(); Thread.sleep(600)
                fun pull() {
                    var pullX = 0f
                    var pullStart = 0f
                    var pullEnd = 0f
                    scenario.onActivity { activity ->
                    val refresh = activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.review_refresh)
                    assertTrue(refresh.isEnabled)
                    val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.review_scroll)
                    scroll.scrollTo(0, 0)
                    val bounds = Rect()
                    assertTrue(scroll.getGlobalVisibleRect(bounds))
                    pullX = bounds.exactCenterX()
                    pullStart = bounds.top + BriefReviewViews.dp(activity, 120).toFloat()
                    pullEnd = minOf(bounds.bottom - 40f, pullStart + BriefReviewViews.dp(activity, 400))
                    }
                    val downTime = android.os.SystemClock.uptimeMillis()
                    fun touch(action: Int, y: Float) {
                        val event = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, pullX, y, 0)
                        instrumentation.sendPointerSync(event)
                        event.recycle()
                    }
                    touch(android.view.MotionEvent.ACTION_DOWN, pullStart)
                    for (step in 1..16) { android.os.SystemClock.sleep(16); touch(android.view.MotionEvent.ACTION_MOVE, pullStart + (pullEnd - pullStart) * step / 16) }
                    touch(android.view.MotionEvent.ACTION_UP, pullEnd)
                    instrumentation.waitForIdleSync(); Thread.sleep(450)
                }
                pull()
                scenario.onActivity { activity ->
                    assertEquals("The first pull expands the heading", 0, activity.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.review_app_bar).top)
                    assertNull("The first pull must not regenerate", findText(activity.findViewById(R.id.review_root), context.getString(R.string.review_error_local)))
                }
                pull()
                waitForText(scenario, context.getString(R.string.review_error_local))
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.review_root)
                    assertNotNull(findText(root, context.getString(R.string.review_previous_kept)))
                    assertNotNull(findText(root, context.getString(R.string.review_next_steps_intro)))
                    assertNotNull(findText(root, context.getString(R.string.review_change_mode)))
                    assertFalse(activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.review_refresh).isRefreshing)
                    // Repeated explicit refreshes in a blocked mode should not leave a stuck spinner.
                    activity.findViewById<View>(R.id.review_refresh_action).performClick()
                }
                saveScreen("review-annotated-local-error-dark.png")
                assertEquals(saved, BriefPublishedReviewStore.read(context, target))
            }
        } } finally { image.delete() }
    }

    @Test fun quotedTweetContextSurvivesSavingAndAppearsAsASeparateCompactSource() = withSettings {
        val image = fixtureMedia()
        try { withAccount { account, cacheKey ->
            val now = System.currentTimeMillis()
            val original = posts(account, now).first().copy(media = emptyList(), quotedPost = com.tjg.twidget.analytics.QuotedPost(
                "https://x.com/source/status/9", "We shipped a tool for comparing tweets at similar ages.", "Quoted source", "source",
                media = listOf(PostMedia("photo", FIXTURE_URL, "Comparison tool", 1280, 768))))
            context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE).edit().putString(cacheKey,
                JSONObject().put("userName", account).put("followers", 100).put("postsAnalyzed", 1)
                    .put("windowDays", 7).put("cachedAt", now).put("recentPosts", JSONArray().put(AnalyticsClient.serializePost(original))).toString()).commit()
            val target = BriefReviewTarget(account, postUrl = original.url)
            val subject = BriefReviewSubjects.load(context, target)!!
            assertTrue(subject.input.media.single().quoted)
            val result = feedback(subject).copy(inspectedMediaIds = setOf("m0"))
            BriefPublishedReviewStore.save(context, subject, result, TwidgetStore.accountGeneration(context, account))
            val restored = BriefPublishedReviewStore.read(context, target)!!
            instrumentation.runOnMainSync {
                val both = original.copy(media = original.quotedPost!!.media.map { it.copy(alt = "Your attachment") })
                val card = BriefReviewTweetCard.create(context, account, post = both, reviewPreview = true) as ViewGroup
                val ownMedia = card.walk().filterIsInstance<com.tjg.twidget.ui.MediaAspectImageView>().first { it.contentDescription == "Your attachment" }
                val quote = card.findViewById<View>(R.id.review_quoted_tweet_card)
                assertTrue("The quoted tweet must follow the main tweet's media", card.indexOfChild(ownMedia) < card.indexOfChild(quote))
            }
            assertEquals(original.quotedPost, restored.subject.post!!.quotedPost)
            assertEquals(subject.input, restored.subject.input)
            ActivityScenario.launch<BriefTweetReviewActivity>(BriefTweetReviewActivity.intent(context, target)).use { scenario ->
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO }
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.review_root)
                    assertNotNull(findText(root, original.quotedPost!!.text))
                    assertNotNull(findText(root, "Quoted source"))
                    val preview = activity.findViewById<ViewGroup>(R.id.review_content).getChildAt(0) as ViewGroup
                    val card = (preview.getChildAt(0) as ViewGroup).getChildAt(0)
                    assertTrue("A quoted source must also fit a compact header", card.height <= BriefReviewViews.dp(activity, 340))
                }
                waitForQuotedMedia(scenario)
                saveScreen("review-quoted-context-light.png")
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES }
                waitForText(scenario, context.getString(R.string.review_next_steps_intro))
                waitForQuotedMedia(scenario)
                saveScreen("review-quoted-context-dark.png")
            }
        } } finally { image.delete() }
    }

    @Test fun publicCardsUseOpenButtonsKeepQuotesClickableAndDoNotOpenFromBodyOrMedia() = withSettings {
        val image = fixtureMedia()
        try { withAccount { account, _ ->
            val now = System.currentTimeMillis()
            val source = com.tjg.twidget.analytics.QuotedPost("https://x.com/source/status/9", "Coming soon...", "Quoted source", "source", timestamp = now - 2 * 3_600_000L)
            val original = posts(account, now).first().copy(authorName = "Your account", quotedPost = source)
            val opened = java.util.Collections.synchronizedList(mutableListOf<String>())
            val packages = java.util.Collections.synchronizedList(mutableListOf<String?>())
            val monitor = object : android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                    if (intent.action != android.content.Intent.ACTION_VIEW) return null
                    opened += intent.dataString.orEmpty()
                    packages += intent.`package`
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                ActivityScenario.launch(BriefCoachTestActivity::class.java).use { scenario ->
                    var parent: ViewGroup? = null
                    var quote: View? = null
                    var photo: View? = null
                    var reviews = 0
                    fun mount(activity: BriefCoachTestActivity, photoStrip: Boolean = false) {
                        val root = android.widget.FrameLayout(activity).apply { setBackgroundResource(R.drawable.brief_screen_background) }
                        val scroll = androidx.core.widget.NestedScrollView(activity)
                        val content = BriefReviewViews.column(activity).apply { setPadding(0, 30, 0, 30) }
                        val width = BriefReviewViews.dp(activity, 392)
                        val noMedia = BriefReviewTweetCard.create(activity, account, post = original.copy(media = emptyList()), compact = true,
                            actions = listOf(BriefReviewAction(R.string.review_view) { reviews++ })) as ViewGroup
                        val withMedia = BriefReviewTweetCard.create(activity, account,
                            post = if (photoStrip) original.copy(media = original.media + original.media) else original, compact = true,
                            actions = listOf(BriefReviewAction(R.string.review_view) { reviews++ })) as ViewGroup
                        content.addView(noMedia, BriefReviewViews.params(activity))
                        content.addView(withMedia, BriefReviewViews.params(activity, 20))
                        scroll.addView(content, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL))
                        root.addView(scroll)
                        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
                            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                            insets
                        }
                        activity.setContentView(root)
                        androidx.core.view.ViewCompat.requestApplyInsets(root)
                        parent = noMedia
                        quote = noMedia.findViewById(R.id.review_quoted_tweet_card)
                        val ownMedia = withMedia.walk().filterIsInstance<android.widget.HorizontalScrollView>().firstOrNull()
                            ?: withMedia.walk().filterIsInstance<com.tjg.twidget.ui.MediaAspectImageView>().first()
                        photo = ownMedia
                        val embeddedQuote = withMedia.findViewById<View>(R.id.review_quoted_tweet_card)
                        assertTrue(withMedia.indexOfChild(ownMedia) < withMedia.indexOfChild(embeddedQuote))
                        var action = findText(withMedia, activity.getString(R.string.review_view))!! as View
                        while (action.parent !== withMedia) action = action.parent as View
                        assertTrue("Quote review actions sit below the quote", withMedia.indexOfChild(action) > withMedia.indexOfChild(embeddedQuote))
                        assertEquals(BriefReviewViews.dp(activity, 18).toFloat(), (embeddedQuote.background as android.graphics.drawable.GradientDrawable).cornerRadius)
                        assertTrue(noMedia.clipToOutline)
                        assertTrue(embeddedQuote.clipToOutline)
                        assertFalse("The public tweet body is no longer a tap target", noMedia.isClickable)
                        assertFalse(noMedia.hasOnClickListeners())
                        assertNull(noMedia.foreground)
                        assertTrue(embeddedQuote.foreground is android.graphics.drawable.RippleDrawable)
                        for ((card, radius) in listOf(embeddedQuote to 18)) {
                            val mask = (card.foreground as android.graphics.drawable.RippleDrawable)
                                .findDrawableByLayerId(android.R.id.mask) as android.graphics.drawable.GradientDrawable
                            assertEquals(BriefReviewViews.dp(activity, radius).toFloat(), mask.cornerRadius)
                        }
                        assertNotNull(findText(embeddedQuote, "Quoted source"))
                        assertNull(findText(embeddedQuote, activity.getString(R.string.review_quoted_tweet)))
                        for (card in listOf(noMedia, withMedia)) {
                            assertNotNull(findText(card, activity.getString(R.string.brief_action_open_tweet)))
                            assertNotNull(findText(card, activity.getString(R.string.review_view)))
                        }
                    }
                    fun tap(view: View, y: Float = view.height / 2f) {
                        val location = IntArray(2)
                        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
                        val at = android.os.SystemClock.uptimeMillis()
                        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                            val event = android.view.MotionEvent.obtain(at, android.os.SystemClock.uptimeMillis(), action,
                                location[0] + view.width / 2f, location[1] + y, 0)
                            instrumentation.sendPointerSync(event); event.recycle()
                        }
                        instrumentation.waitForIdleSync()
                    }
                    fun awaitPhoto() {
                        val deadline = System.currentTimeMillis() + 8_000L
                        do {
                            var ready = false
                            scenario.onActivity {
                                val images = photo!!.walk().filterIsInstance<com.tjg.twidget.ui.MediaAspectImageView>().toList()
                                ready = photo!!.isAttachedToWindow && images.isNotEmpty() && images.all { it.drawable != null && it.width > 0 && it.height > 0 }
                            }
                            if (ready) { instrumentation.waitForIdleSync(); return }
                            Thread.sleep(80)
                        } while (System.currentTimeMillis() < deadline)
                        fail("The card's photos should be visible before checking taps and styling")
                    }
                    fun settlePresses() {
                        scenario.onActivity {
                            listOf(parent!!, quote!!, photo!!).forEach { it.isPressed = false; it.jumpDrawablesToCurrentState() }
                        }
                        instrumentation.waitForIdleSync(); Thread.sleep(500)
                    }
                    scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO }
                    instrumentation.waitForIdleSync(); Thread.sleep(300)
                    scenario.onActivity { mount(it) }
                    awaitPhoto()
                    saveScreen("public-cards-before-taps.png")
                    tap(parent!!, BriefReviewViews.dp(context, 28).toFloat())
                    assertTrue("Tapping the body must not open X", opened.isEmpty())
                    tap(quote!!)
                    assertEquals(listOf(source.url), opened.toList())
                    scenario.onActivity {
                        findText(parent!!, context.getString(R.string.brief_action_open_tweet))!!.performClick()
                    }
                    assertEquals(listOf(source.url, original.url), opened.toList())
                    assertEquals(listOf("com.twitter.android", "com.twitter.android"), packages.toList())
                    settlePresses()
                    scenario.onActivity {
                        findText(parent!!, context.getString(R.string.review_view))!!.performClick()
                        assertEquals(1, reviews)
                        assertEquals(2, opened.size)
                        quote!!.isPressed = true
                        quote!!.drawableHotspotChanged(quote!!.width / 2f, quote!!.height / 2f)
                        quote!!.jumpDrawablesToCurrentState()
                    }
                    instrumentation.waitForIdleSync(); Thread.sleep(150)
                    saveScreen("quoted-cards-pressed-quote-light.png")
                    scenario.onActivity { quote!!.isPressed = false }
                    tap(photo!!)
                    assertEquals("Tapping the main media must not open X", 2, opened.size)
                    settlePresses()
                    saveScreen("quoted-cards-light.png")
                    scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES }
                    instrumentation.waitForIdleSync(); Thread.sleep(300)
                    scenario.onActivity { mount(it) }
                    awaitPhoto()
                    tap(quote!!)
                    assertEquals(source.url, opened.last())
                    settlePresses()
                    settlePresses()
                    saveScreen("quoted-cards-dark.png")
                    scenario.onActivity { mount(it, photoStrip = true) }
                    awaitPhoto()
                    val beforeStripTap = opened.size
                    tap(photo!!)
                    assertEquals("A main photo strip tap must not open X", beforeStripTap, opened.size)
                    val location = IntArray(2)
                    instrumentation.runOnMainSync { photo!!.getLocationOnScreen(location) }
                    val down = android.os.SystemClock.uptimeMillis()
                    for (step in 0..8) {
                        val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 8 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                        val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action,
                            location[0] + photo!!.width * (0.8f - step * 0.075f), location[1] + photo!!.height / 2f, 0)
                        instrumentation.sendPointerSync(event); event.recycle(); Thread.sleep(20)
                    }
                    instrumentation.waitForIdleSync()
                    assertEquals("Swiping photos must not open the tweet", beforeStripTap, opened.size)
                    scenario.onActivity { assertTrue((photo!! as android.widget.HorizontalScrollView).scrollX > 0) }
                    for ((mode, theme) in listOf(AppCompatDelegate.MODE_NIGHT_NO to "light", AppCompatDelegate.MODE_NIGHT_YES to "dark")) {
                        scenario.onActivity { it.delegate.localNightMode = mode }
                        instrumentation.waitForIdleSync(); Thread.sleep(300)
                        scenario.onActivity { activity ->
                            val content = BriefReviewViews.column(activity).apply { setPadding(0, 30, 0, 30) }
                            for (media in listOf(emptyList(), original.media)) {
                                val card = BriefReviewTweetCard.create(activity, account,
                                    post = original.copy(quotedPost = null, media = media), compact = true,
                                    actions = listOf(BriefReviewAction(R.string.review_view) { reviews++ }))
                                content.addView(card, BriefReviewViews.params(activity, 20))
                                if (media.isNotEmpty()) photo = card.walk().filterIsInstance<com.tjg.twidget.ui.MediaAspectImageView>().first()
                            }
                            activity.setContentView(android.widget.FrameLayout(activity).apply {
                                setBackgroundResource(R.drawable.brief_screen_background)
                                addView(content, android.widget.FrameLayout.LayoutParams(BriefReviewViews.dp(activity, 392),
                                    ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
                            })
                        }
                        awaitPhoto()
                        saveScreen("public-cards-no-quote-$theme.png")
                    }
                    scenario.onActivity { activity ->
                        for (language in listOf("en", "fr", "de")) {
                            val config = android.content.res.Configuration(activity.resources.configuration).apply {
                                setLocale(java.util.Locale.forLanguageTag(language)); fontScale = 1.4f
                            }
                            val localized = androidx.appcompat.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Twidget)
                            val card = BriefReviewTweetCard.create(localized, account,
                                post = original.copy(quotedPost = null, media = emptyList()), compact = true,
                                actions = listOf(BriefReviewAction(R.string.review_view) {})) as ViewGroup
                            val width = BriefReviewViews.dp(localized, 300)
                            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            card.layout(0, 0, width, card.measuredHeight)
                            val buttons = card.walk().filterIsInstance<androidx.appcompat.widget.AppCompatButton>().toList()
                            assertEquals(2, buttons.size)
                            for (button in buttons) {
                                val bounds = Rect(0, 0, button.width, button.height)
                                card.offsetDescendantRectToMyCoords(button, bounds)
                                assertTrue("$language button stays within a narrow card", bounds.left >= 0 && bounds.right <= width)
                                assertTrue("$language button retains its touch height", button.height >= BriefReviewViews.dp(localized, 48))
                                assertTrue("$language label is not clipped", button.layout.getEllipsisCount(0) == 0)
                            }
                        }
                    }
                }
            } finally { instrumentation.removeMonitor(monitor) }
        } } finally { image.delete() }
    }

    @Test fun briefPreviewDescriptionsSwitchFromInvitationToSavedPerformanceWithoutRegeneration() = withSettings {
        withAccount { account, cacheKey ->
            val now = System.currentTimeMillis()
            val posts = posts(account, now).map { it.copy(media = emptyList()) }
            val analytics = AnalyticsClient.analyticsFromPosts(account, 100, posts.size, false, posts, cachedAt = now)
            context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE).edit().putString(cacheKey,
                JSONObject().put("userName", account).put("followers", 100).put("postsAnalyzed", posts.size)
                    .put("windowDays", 7).put("cachedAt", now).put("recentPosts", JSONArray(posts.map(::json))).toString()).commit()
            val target = BriefReviewTarget(account, postUrl = posts.first().url)
            val subject = BriefReviewSubjects.load(context, target)!!
            val strings = BriefStrings.from(context)
            val invite = BriefReviewPreviewCopy.description(posts.first(), null, strings, now)
            ActivityScenario.launch(BriefCoachTestActivity::class.java).use { scenario ->
                var items: BriefPostCoachSection.Items? = null
                var observer: Job? = null
                var firstPreview: View? = null
                var firstCard: View? = null
                fun mount(activity: BriefCoachTestActivity) {
                    observer?.cancel()
                    items = BriefPostCoachSection.create(activity, account, analytics)!!
                    val content = activity.findViewById<LinearLayout>(R.id.review_content)
                    content.removeAllViews()
                    items!!.previews.forEach { content.addView(it, BriefReviewViews.params(activity, 20)) }
                    firstPreview = items!!.previews.first()
                    firstCard = (firstPreview as ViewGroup).getChildAt(1)
                    observer = activity.lifecycleScope.launch {
                        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            BriefPublishedReviewStore.updates.collect { items!!.refreshDescriptions() }
                        }
                    }
                }
                scenario.onActivity { activity ->
                    activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_NO
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.setContentView(R.layout.activity_brief_tweet_review)
                    BriefPageChrome.review(activity.findViewById(R.id.review_root), R.string.review_title) { }
                    mount(activity)
                    assertNotNull(findText(firstPreview!!, invite))
                    assertTrue(invite.startsWith("You posted this 1 day ago."))
                }
                instrumentation.waitForIdleSync(); Thread.sleep(350)
                saveScreen("review-preview-invitation-light.png")
                val saved = BriefPublishedReviewStore.save(context, subject, feedback(subject), TwidgetStore.accountGeneration(context, account))!!
                val summary = BriefReviewPreviewCopy.description(posts.first(), saved, strings, now)
                val deadline = System.currentTimeMillis() + 5_000
                var updated = false
                do {
                    scenario.onActivity { updated = findText(firstPreview!!, summary) != null }
                    if (!updated) Thread.sleep(60)
                } while (!updated && System.currentTimeMillis() < deadline)
                assertTrue("A successful review must update the already-visible Brief preview", updated)
                scenario.onActivity {
                    assertSame("The summary changes without replacing or reranking its card", firstCard, (firstPreview as ViewGroup).getChildAt(1))
                    assertTrue(summary.contains("1,164 views and 23 public interactions"))
                    assertNull(findText(firstPreview!!, invite))
                    val second = items!!.previews[1]
                    assertTrue((second as ViewGroup).getChildAt(0).let { it as TextView }.text.toString().contains("Review this tweet"))
                }
                instrumentation.waitForIdleSync(); Thread.sleep(350)
                saveScreen("review-preview-performance-light.png")
                scenario.onActivity { it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.setContentView(R.layout.activity_brief_tweet_review)
                    BriefPageChrome.review(activity.findViewById(R.id.review_root), R.string.review_title) { }
                    mount(activity)
                }
                val darkDeadline = System.currentTimeMillis() + 5_000
                do {
                    scenario.onActivity { updated = findText(firstPreview!!, summary) != null }
                    if (!updated) Thread.sleep(60)
                } while (!updated && System.currentTimeMillis() < darkDeadline)
                assertTrue("Reopened previews must reuse the saved review", updated)
                instrumentation.waitForIdleSync(); Thread.sleep(350)
                saveScreen("review-preview-performance-dark.png")
                scenario.onActivity { observer?.cancel() }
            }
        }
    }

    @Test fun tweetReviewsAreFirstInManageContentWithoutInvalidatingGeneratedCards() = withSettings {
        ActivityScenario.launch(BriefContentSettingsActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as PreferenceFragmentCompat
                val first = fragment.preferenceScreen.getPreference(0)
                assertEquals("brief_content_tweet_reviews", first.key)
                assertTrue(first.title.toString().startsWith(context.getString(R.string.brief_content_tweet_reviews)))
                saveImage(activity.window.decorView, "review-manage-content.png")
                val fingerprint = BriefSettingsStore.contentFingerprint(activity)
                BriefSettingsStore.clearContentRegenerationPending(activity)
                assertTrue(first.callChangeListener(false))
                assertFalse(BriefSettingsStore.contentEnabled(activity, BriefContentCategory.TWEET_REVIEWS))
                assertFalse(BriefSettingsStore.contentRegenerationPending(activity))
                assertEquals(fingerprint, BriefSettingsStore.contentFingerprint(activity))
            }
        }
    }

    @Test fun missingAndStaleAnalyticsRecoverBeforeDeclaringTheTweetUnavailable() = withSettings {
        withAccount { account, cacheKey -> kotlinx.coroutines.runBlocking {
            val now = System.currentTimeMillis()
            val posts = posts(account, now).map { it.copy(media = emptyList()) }
            val target = BriefReviewTarget(account, postUrl = posts.first().url)
            val prefs = context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE)
            fun cache(at: Long) {
                prefs.edit().putString(cacheKey, JSONObject().put("userName", account).put("followers", 100)
                    .put("postsAnalyzed", posts.size).put("windowDays", 7).put("cachedAt", at)
                    .put("recentPosts", JSONArray(posts.map(::json))).toString()).commit()
            }
            for (stale in listOf(false, true)) {
                prefs.edit().remove(cacheKey).commit()
                if (stale) cache(now - 3 * 86_400_000L)
                assertNull(BriefReviewSubjects.load(context, target))
                assertTrue(BriefReviewSubjects.needsAnalyticsRefresh(context, target))
                var refreshes = 0
                val recovered = BriefReviewSubjects.recoverMissing(target,
                    load = { BriefReviewSubjects.load(context, target) },
                    needsRefresh = { BriefReviewSubjects.needsAnalyticsRefresh(context, target) },
                    refresh = { refreshes++; cache(now) })
                assertEquals(1, refreshes)
                assertEquals(target.postUrl, recovered!!.post!!.url)
                assertFalse(BriefReviewSubjects.needsAnalyticsRefresh(context, target))
            }
        } }
    }





    private fun saveLibraryPreview(activity: android.app.Activity, nightMode: Int, filename: String) {
        val config = android.content.res.Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        val themed = android.view.ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Twidget)
        val root = android.widget.FrameLayout(themed).apply { setBackgroundResource(R.drawable.brief_screen_background) }
        val card = BriefReviewLibraryCard.create(themed) {}
        root.addView(card, android.widget.FrameLayout.LayoutParams(BriefReviewViews.dp(themed, 392), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.measure(View.MeasureSpec.makeMeasureSpec(BriefReviewViews.dp(themed, 392), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val art = card.walk().filterIsInstance<android.widget.ImageView>().single()
        assertEquals(BriefReviewViews.dp(themed, 89), art.width)
        assertEquals(BriefReviewViews.dp(themed, 80), art.height)
        assertNotNull(art.drawable)
        assertNotNull(findText(card, themed.getString(R.string.review_library_title)))
        assertTrue(card.findViewById<View>(R.id.review_library_card).height >= BriefReviewViews.dp(themed, 108))
        saveImage(root, filename)
    }

    private fun feedback(subject: BriefReviewSubject): BriefCoachGeneration.Success {
        val refs = listOf(BriefCoachCitation("subject", subject.input.text)) + subject.input.history.take(1).map { BriefCoachCitation("p0", it.fullText ?: it.text, it) }
        return BriefCoachGeneration.Success(BriefCoachFeedback("People outside the conversation may need a clearer introduction to m0.",
            listOf(BriefCoachSuggestion("Name the comparison tool and the question you want answered.",
                "q0 leaves the subject implicit. This is a clarity hypothesis, not a measured cause.",
                subject.input.text.substringBefore("\n\n"), "I shipped a tool that compares tweets at similar ages. Which comparison would help you most?", false, refs)), null), false)
    }

    private fun posts(account: String, now: Long) = List(4) { index -> PostSummary(
        "https://x.com/$account/status/${100 + index}", if (index == 0) "Finally shipped this. Thoughts?" else "An age comparison tool for your tweets.",
        if (index == 0) 1164 else 2000, if (index == 0) 12 else 40, 4, 5, 2, 23,
        now - (30 + index) * 3_600_000L, "", "Review fixture", account, "",
        fullText = if (index == 0) "Finally shipped this. Thoughts?" else "An age comparison tool for your tweets.",
        media = listOf(PostMedia("photo", FIXTURE_URL, "Twidget comparison view", 1280, 768))) }
    private fun json(post: PostSummary) = JSONObject().put("url", post.url).put("text", post.text).put("fullText", post.fullText)
        .put("views", post.views).put("likes", post.likes).put("replies", post.replies).put("reposts", post.reposts)
        .put("quotes", post.quotes).put("engagements", post.engagements).put("ts", post.timestamp)
        .put("authorName", post.authorName).put("authorUserName", post.authorUserName).put("authorAvatar", "").put("viewsKnown", true)
        .put("media", JSONArray(post.media.map { JSONObject().put("type", it.type).put("url", it.url).put("alt", it.alt).put("width", it.width).put("height", it.height) }))
    private fun withSettings(block: () -> Unit) {
        val prefs = context.getSharedPreferences("twidget_brief_settings", Context.MODE_PRIVATE)
        val keys = listOf("enabled", "provider", "content_tweet_reviews", "content_posting_guidance", "content_regeneration_pending", "reviews_settings_seen")
        val backup = File(context.cacheDir, "brief_review_test_settings_backup.json")
        fun restore(values: Map<String, Any?>) {
            prefs.edit().apply { values.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value); is String -> putString(key, value); else -> remove(key)
            } } }.commit()
        }
        if (backup.isFile) {
            val previous = JSONObject(backup.readText())
            restore(keys.associateWith { previous.opt(it) })
            backup.delete()
        }
        // Explicit recovery arguments are only used after an interrupted test from before durable backups.
        InstrumentationRegistry.getArguments().getString("restoreReviewProvider")?.let { provider ->
            require(provider in setOf("auto", "cloud", "local"))
            restore(mapOf("provider" to provider, "enabled" to true, "content_posting_guidance" to true))
        }
        val store = ScheduleStore(context)
        TwidgetStore.accounts(context).filter { it.matches(Regex("rv_\\d{8}")) }.forEach { account ->
            val fixtures = store.listForAccount(account)
            if (fixtures.size == 3 && fixtures.map { it.thread.singleOrNull()?.text }.toSet() ==
                setOf("Draft fixture 0", "Draft fixture 1", "Draft fixture 2")) {
                store.removeAll(fixtures.map { it.id }.toSet())
                TwidgetStore.removeAccount(context, account)
            }
        }
        val saved = keys.associateWith { prefs.all[it] }
        backup.writeText(JSONObject().apply { saved.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }.toString())
        prefs.edit().putBoolean("enabled", false).putString("provider", "local")
            .putBoolean("content_tweet_reviews", true).putBoolean("content_posting_guidance", false).putBoolean("reviews_settings_seen", false).commit()
        try { block() } finally {
            restore(saved)
            backup.delete()
        }
    }
    private fun withAccount(block: (String, String) -> Unit) {
        val account = "rv_${System.nanoTime().toString().takeLast(8)}"
        TwidgetStore.addAccount(context, account)
        val cacheKey = "v7:${TwidgetStore.settings(context).dataSource}:$account"
        try { block(account, cacheKey) } finally { TwidgetStore.removeAccount(context, account) }
    }
    private fun waitForQuotedMedia(scenario: ActivityScenario<BriefTweetReviewActivity>) {
        val deadline = System.currentTimeMillis() + 8_000L
        var details = ""
        do {
            var loaded = false
            scenario.onActivity { activity ->
                val images = activity.findViewById<View>(R.id.review_root).walk().filterIsInstance<com.tjg.twidget.ui.MediaAspectImageView>().toList()
                loaded = images.any { it.drawable != null && it.visibility == View.VISIBLE && it.height > 0 }
                details = images.joinToString { "height=${it.height}, visible=${it.visibility}, drawable=${it.drawable != null}" }
            }
            if (loaded) { instrumentation.waitForIdleSync(); return }
            Thread.sleep(80)
        } while (System.currentTimeMillis() < deadline)
        fail("Quoted media should actually render: $details")
    }

    private fun waitForText(scenario: ActivityScenario<BriefTweetReviewActivity>, value: String) {
        val deadline = System.currentTimeMillis() + 8_000L
        do {
            var found = false
            scenario.onActivity { found = findText(it.findViewById(R.id.review_root), value) != null }
            if (found) { instrumentation.waitForIdleSync(); Thread.sleep(450); return }
            Thread.sleep(80)
        } while (System.currentTimeMillis() < deadline)
        fail("Expected review card did not appear")
    }
    private fun View.walk(): Sequence<View> = sequence { yield(this@walk); if (this@walk is ViewGroup) for (index in 0 until childCount) yieldAll(getChildAt(index).walk()) }
    private fun findText(root: View, value: String): TextView? = root.walk().filterIsInstance<TextView>().firstOrNull { it.text.toString() == value }
    private fun saveImage(view: View, name: String) {
        if (view.width <= 0 || view.height <= 0) return
        val bitmap = Bitmap.createBitmap(view.width, view.height.coerceAtMost(16000), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun saveScreen(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun fixtureMedia(): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(FIXTURE_URL.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(context.cacheDir, "tweet_media_$hash.img")
        val bitmap = Bitmap.createBitmap(1280, 768, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(12, 27, 43))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 55f }
            drawText("Twidget · compare your tweets", 65f, 110f, paint)
            paint.color = Color.rgb(161, 213, 255)
            drawRoundRect(60f, 170f, 615f, 680f, 45f, 45f, paint)
            drawRoundRect(650f, 170f, 1215f, 680f, 45f, 45f, paint)
            paint.color = Color.rgb(12, 27, 43); paint.textSize = 45f
            drawText("Views", 110f, 255f, paint); drawText("Interactions", 700f, 255f, paint)
            paint.textSize = 125f
            drawText("1,164", 110f, 460f, paint); drawText("23", 760f, 460f, paint)
            paint.textSize = 36f
            drawText("Similar observed ages", 110f, 600f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
    private companion object { const val FIXTURE_URL = "https://example.invalid/twidget-review-instrumented-fixture.png" }
}
