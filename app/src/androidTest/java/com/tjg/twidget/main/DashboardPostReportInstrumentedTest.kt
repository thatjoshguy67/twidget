package com.tjg.twidget.main

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.brief.*
import com.tjg.twidget.data.TwidgetStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DashboardPostReportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun allThreeCardsOpenTheirOwnReportsAndFitBothThemes() = withFixture { account, cacheKey ->
        val now = System.currentTimeMillis()
        fun post(id: Int, hours: Long, views: Long) = PostSummary("https://x.com/$account/status/$id",
            "I shipped a tool that compares tweets at similar ages.", views, 12, 4, 2, 0, 18,
            now - hours * 3_600_000L, "", "Dashboard fixture", account, "",
            fullText = "I shipped a tool that compares tweets at similar ages.")
        val best = post(101, 36, 123_456_789)
        val worst = post(102, 48, 123)
        val banger = post(103, 365 * 24, 999_999_999).copy(authorUserName = "${account}_old",
            url = "https://x.com/${account}_old/status/103")
        // Reproduce the legacy renamed-account cache, which predates persisted handle redirects.
        context.getSharedPreferences("twidget_bangers", Context.MODE_PRIVATE).edit().putString("latest:$account",
            JSONObject().put("post", AnalyticsClient.serializePost(banger)).put("complete", true).toString()).commit()
        val snapshot = AnalyticsClient.analyticsFromPosts(account, 100, 2, false, listOf(best, worst), cachedAt = now).copy(
            best = best, worst = worst, banger = banger, bangerComplete = true)
        context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE).edit().putString(cacheKey,
            JSONObject().put("userName", account).put("cachedAt", now).put("windowDays", 7)
                .put("postsAnalyzed", 2).put("best", AnalyticsClient.serializePost(best))
                .put("worst", AnalyticsClient.serializePost(worst)).put("banger", AnalyticsClient.serializePost(banger))
                .put("recentPosts", JSONArray(listOf(best, worst).map(AnalyticsClient::serializePost))).toString()).commit()
        val opened = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == BriefTweetReviewActivity::class.java.name || intent.action == Intent.ACTION_VIEW) {
                    opened += Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.addMonitor(monitor)
                for ((mode, theme) in listOf(AppCompatDelegate.MODE_NIGHT_NO to "light", AppCompatDelegate.MODE_NIGHT_YES to "dark")) {
                    scenario.onActivity { it.delegate.localNightMode = mode }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        activity.analytics = snapshot
                        for ((type, selected) in listOf(DashboardCardType.ALL_TIME_POST to banger,
                            DashboardCardType.BEST_POST to best, DashboardCardType.WORST_POST to worst)) {
                            val card = activity.postAnalyticsBinder.createGridCard(type, account)
                            val textSizes = card.walk().filterIsInstance<TextView>().associateWith { it.textSize }
                            for (scale in listOf(1f, 1.6f)) {
                                textSizes.forEach { (view, size) -> view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * scale) }
                                for (widthDp in listOf(392, 280)) {
                                    val width = activity.dp(widthDp)
                                    repeat(2) {
                                        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                                        card.layout(0, 0, width, card.measuredHeight)
                                    }
                                    val button = card.findViewById<TextView>(R.id.dashboard_view_report)
                                    assertNotNull(button)
                                    val buttonBounds = android.graphics.Rect(0, 0, button.width, button.height)
                                    (card as ViewGroup).offsetDescendantRectToMyCoords(button, buttonBounds)
                                    assertEquals("The button adds no blank space below the footer", card.paddingBottom,
                                        card.height - buttonBounds.bottom)
                                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                                        val tapBounds = card.touchDelegate.touchDelegateInfo.getRegionAt(0).bounds
                                        assertTrue("The compact pill keeps its larger hit area", tapBounds.height() >= activity.dp(48) - 1)
                                    }
                                    assertEquals(activity.getString(R.string.dashboard_view_report), button.text.toString())
                                    assertTrue("Report label fits", button.paint.measureText(button.text.toString()) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1)
                                    card.walk().filterIsInstance<TextView>().filter { it != button && it.text.toString().matches(Regex("[\\d.,]+[KMB]?")) }.forEach {
                                        assertTrue("Stat fits at $widthDp dp", it.paint.measureText(it.text.toString()) <= it.width + 1)
                                    }
                                    val bitmap = Bitmap.createBitmap(width, card.height, Bitmap.Config.ARGB_8888)
                                    card.draw(Canvas(bitmap))
                                    File(activity.filesDir, "dashboard-report-${type.id}-$theme-$widthDp${if (scale > 1f) "-large" else ""}.png").outputStream().use {
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    bitmap.recycle()
                                }
                            }
                            val before = opened.size
                            card.findViewById<View>(R.id.dashboard_view_report).performClick()
                            assertEquals(before + 1, opened.size)
                            val intent = opened.last()
                            assertEquals(BriefTweetReviewActivity::class.java.name, intent.component!!.className)
                            assertEquals(account, intent.getStringExtra("review_username"))
                            assertEquals(selected.url, intent.getStringExtra("review_post_url"))
                            assertTrue(intent.getBooleanExtra("review_from_dashboard", false))
                            assertTrue(card.performClick())
                            assertEquals(selected.url, opened.last().dataString)
                        }
                        activity.analytics = null
                        for (type in listOf(DashboardCardType.BEST_POST, DashboardCardType.WORST_POST))
                            assertNull(activity.postAnalyticsBinder.createGridCard(type, account).findViewById<View>(R.id.dashboard_view_report))
                    }
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
        // Manage content hides Brief previews, but an explicit dashboard report remains accessible.
        assertFalse(BriefSettingsStore.contentEnabled(context, BriefContentCategory.TWEET_REVIEWS))
        val target = BriefReviewTarget(account, postUrl = banger.url, fromDashboard = true)
        val subject = requireNotNull(BriefReviewSubjects.load(context, target))
        assertEquals(banger.url, subject.post!!.url)
        assertEquals(account, subject.post.authorUserName)
        assertEquals(0L, subject.input.reviewEvidence!!.observedAt)
        assertTrue(subject.input.history.isEmpty())
        ActivityScenario.launch<BriefTweetReviewActivity>(BriefTweetReviewActivity.intent(context, target)).use { scenario ->
            val deadline = System.currentTimeMillis() + 8000L
            var state = BriefReviewState()
            do {
                scenario.onActivity { state = androidx.lifecycle.ViewModelProvider(it)[BriefReviewSession::class.java].state.value }
                if (state.phase == BriefReviewPhase.FAILED) break
                Thread.sleep(50)
            } while (System.currentTimeMillis() < deadline)
            assertEquals(BriefReviewPhase.FAILED, state.phase)
            assertEquals(BriefReviewFailure.LOCAL_ONLY, state.failure)
            assertEquals(banger.url, state.subject!!.post!!.url)
        }
        val feedback = BriefCoachGeneration.Success(BriefCoachFeedback(
            "Your saved totals show reach, but cannot prove why the tweet performed this way.",
            listOf(BriefCoachSuggestion("Show an example of the comparison.",
                "Your wording names the tool; an example could help readers picture how to use it.",
                subject.input.text, null, false, listOf(BriefCoachCitation("subject", subject.input.text)))), null,
            panelIds = listOf("performance")), cloud = true)
        val saved = requireNotNull(BriefPublishedReviewStore.save(context, subject, feedback,
            TwidgetStore.accountGeneration(context, account)))
        val otherLanguage = BriefStrings.from(context, if (subject.input.language.startsWith("fr")) "en" else "fr")
        val localized = BriefReviewSubjects.fromSaved(saved, otherLanguage)
        assertEquals(otherLanguage.languageTag, localized.input.language)
        assertEquals(0L, localized.input.reviewEvidence!!.observedAt)
        ActivityScenario.launch<BriefTweetReviewActivity>(BriefTweetReviewActivity.intent(context, target)).use { scenario ->
            val deadline = System.currentTimeMillis() + 8000L
            var state = BriefReviewState()
            do {
                scenario.onActivity { state = androidx.lifecycle.ViewModelProvider(it)[BriefReviewSession::class.java].state.value }
                if (state.phase == BriefReviewPhase.COMPLETE) break
                Thread.sleep(50)
            } while (System.currentTimeMillis() < deadline)
            assertEquals("Reopening uses the shared saved review even in Local mode", BriefReviewPhase.COMPLETE, state.phase)
            assertEquals(saved.savedAt, state.savedAt)
        }
    }

    @Test fun secondaryProfilesHideReviewsAndDefaultChangesDisableStaleButtons() = withFixture { account, _ ->
        val other = "other_profile"
        val post = PostSummary("https://x.com/$account/status/104", "A dashboard review fixture.",
            100, 12, 4, 2, 0, 18, System.currentTimeMillis(), "", "Fixture", account, "")
        val snapshot = AnalyticsClient.analyticsFromPosts(account, 100, 2, false, listOf(post), cachedAt = System.currentTimeMillis()).copy(
            best = post, worst = post, banger = post, bangerComplete = true)
        val opened = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == BriefTweetReviewActivity::class.java.name) {
                    opened += Intent(intent)
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.addMonitor(monitor)
                scenario.onActivity { activity ->
                    activity.analytics = snapshot
                    for (type in listOf(DashboardCardType.ALL_TIME_POST, DashboardCardType.BEST_POST, DashboardCardType.WORST_POST)) {
                        val secondary = activity.postAnalyticsBinder.createGridCard(type, other)
                        assertNull("Added profiles must not expose tweet reviews", secondary.findViewById<View>(R.id.dashboard_view_report))
                        val primary = activity.postAnalyticsBinder.createGridCard(type, account.uppercase())
                        val button = requireNotNull(primary.findViewById<View>(R.id.dashboard_view_report))
                        val before = opened.size
                        button.performClick()
                        assertEquals("Default profile retains its review action", before + 1, opened.size)
                        prefs.edit().putString("username", other).commit()
                        try {
                            button.performClick()
                            assertEquals("A stale action cannot review the old default profile", before + 1, opened.size)
                        } finally { prefs.edit().putString("username", account).commit() }
                    }
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    /** Explicit opt-in: read-only diagnosis of the installed account; never invokes a model. */
    @Test fun currentDashboardBangerResolvesWithoutGenerating() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("checkLiveDashboardReport") == "true")
        val account = TwidgetStore.accounts(context).first()
        val post = requireNotNull(AnalyticsClient.cached(context, account)?.banger)
        val subject = BriefReviewSubjects.load(context, BriefReviewTarget(account, postUrl = post.url, fromDashboard = true))
        assertNotNull("The installed account's old-handle banger must resolve", subject)
        assertEquals(post.url, subject!!.post!!.url)
        assertEquals(account, subject.post.authorUserName)
    }

    private fun View.walk(): Sequence<View> = sequence {
        yield(this@walk)
        if (this@walk is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).walk())
    }

    private fun withFixture(block: (String, String) -> Unit) {
        val main = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val brief = context.getSharedPreferences("twidget_brief_settings", Context.MODE_PRIVATE)
        val mainKeys = listOf("onboarded", "username", "accounts", "refresh_on_launch")
        val briefKeys = listOf("enabled", "onboarding_complete", "provider", "content_tweet_reviews")
        val backup = File(context.cacheDir, "dashboard_report_test_backup.json")
        fun restore(prefs: SharedPreferences, values: JSONObject, keys: List<String>) {
            prefs.edit().apply { keys.forEach { key -> when (val value = values.opt(key)) {
                is String -> putString(key, value); is Boolean -> putBoolean(key, value); else -> remove(key)
            } } }.commit()
        }
        fun recover(saved: JSONObject) {
            TwidgetStore.removeAccount(context, saved.getString("fixture"))
            main.edit().remove("account_redirect_${saved.getString("fixture")}_old").commit()
            restore(main, saved.getJSONObject("main"), mainKeys)
            restore(brief, saved.getJSONObject("brief"), briefKeys)
        }
        if (backup.isFile) { recover(JSONObject(backup.readText())); backup.delete() }
        val account = "dr_${System.nanoTime().toString().takeLast(8)}"
        fun copy(prefs: SharedPreferences, keys: List<String>) = JSONObject().apply {
            keys.forEach { put(it, prefs.all[it] ?: JSONObject.NULL) }
        }
        val saved = JSONObject().put("fixture", account).put("main", copy(main, mainKeys)).put("brief", copy(brief, briefKeys))
        backup.writeText(saved.toString())
        main.edit().putBoolean("onboarded", true).putString("username", account)
            .putString("accounts", JSONArray(listOf(account) + TwidgetStore.accounts(context)).toString())
            .putBoolean("refresh_on_launch", false).commit()
        brief.edit().putBoolean("enabled", false).putBoolean("onboarding_complete", false)
            .putString("provider", "local").putBoolean("content_tweet_reviews", false).commit()
        try { block(account, "v7:${TwidgetStore.settings(context).dataSource}:$account") }
        finally { recover(saved); backup.delete() }
    }
}
