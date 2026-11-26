package com.tjg.twidget.brief

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.widget.BriefWidgetArtworkRenderer
import com.tjg.twidget.widget.TwidgetBriefWidget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefPresentationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val strings get() = BriefStrings.from(context)

    private fun snapshot(account: String, at: Long = System.currentTimeMillis() - 8 * 60 * 60_000L) = BriefSnapshot(
        account, at, 1, 2, 0, 100, 20, 30, 1, 2,
        listOf(BriefCard("growth", BriefCardType.FOLLOWER_CONTEXT, "Your follower update", "You gained 1 follower.", 80,
            narrativeGenerated = true)), headline = "Your audience is growing", subheading = "You gained 1 follower.",
        shortDescription = "Your audience grew.", topFollowerRanks = emptyMap(), providerUsed = BriefProviderUsed.LOCAL,
        language = strings.languageTag, summaryGenerated = true, aiGeneratedAt = at,
        contentSettingsFingerprint = BriefSettingsStore.contentFingerprint(context))

    @Test fun expiredCopySurvivesRebuildsFailedAttemptsAndRestartWithoutSuppressingFreshGeneration() = withAccount { account ->
        val success = snapshot(account)
        BriefStore.write(context, success)
        val remembered = BriefStore.presentation(context, account)!!
        assertFalse(BriefAiCachePolicy.isFresh(success))
        var source = success.copy(providerUsed = BriefProviderUsed.TEMPLATE, summaryGenerated = false,
            cards = success.cards.map { it.copy(narrativeGenerated = false) })
        val before = BriefWidgetArtworkRenderer.render(context, 920, 460, account, source, true,
            strings = strings, useProfileImages = false)
        repeat(6) { index ->
            source = source.copy(sourceSyncedAt = 100L + index, analyticsCachedAt = 200L + index,
                contextFingerprint = "new evidence $index")
            BriefStore.write(context, source)
        }
        BriefStore.resetAi(context, account)
        assertFalse(BriefStore.read(context, account)!!.hasGeneratedNarrative)
        assertNull(BriefStore.cachedRun(context, source))
        assertEquals(remembered, BriefStore.presentation(context, account))
        // Decode the durable value independently, after the successful generation cache has expired.
        val root = JSONObject(context.getSharedPreferences("twidget_briefs", Context.MODE_PRIVATE).getString(account, null)!!)
        assertEquals(0, root.getJSONArray("recentRuns").length())
        assertEquals(success.headline, root.getJSONObject("lastPresentation").getString("title"))
        val after = BriefWidgetArtworkRenderer.render(context, 920, 460, account, source, true,
            strings = strings, useProfileImages = false)
        assertTrue("Widget artwork keeps the last successful copy", before.sameAs(after))
        before.recycle(); after.recycle()
        instrumentation.runOnMainSync {
            val widget = TwidgetBriefWidget.createViews(context, 0, 352, 175, account, source)
                .apply(context, FrameLayout(context))
            assertTrue(widget.findViewById<View>(android.R.id.background).contentDescription.toString().contains(success.headline))
        }
        val next = success.copy(generatedAt = System.currentTimeMillis(), aiGeneratedAt = System.currentTimeMillis(),
            headline = "Your next step is clearer", subheading = "Your recent tweets give you something to build on.")
        BriefStore.write(context, next)
        assertEquals(next.headline, BriefStore.presentation(context, account)!!.title)
        assertTrue(BriefStore.read(context, account)!!.hasGeneratedNarrative)
    }

    @Test fun changedFollowerFactsReplaceOldNumbersWithoutCallingAnyModel() = withAccount { account ->
        val old = snapshot(account).copy(followers = 8094, followersToday = -20, followersWeek = -20,
            headline = "Your follower change is -20.", subheading = "Your follower change was -20, moving you closer to your goal.",
            shortDescription = "Your follower change was -20, moving you closer to your goal.")
        BriefStore.write(context, old)
        val current = old.copy(followers = 8074, followersToday = 4, followersWeek = -25,
            generatedAt = System.currentTimeMillis(), sourceSyncedAt = System.currentTimeMillis(),
            providerUsed = BriefProviderUsed.TEMPLATE, summaryGenerated = false, headline = "", subheading = "", shortDescription = "",
            cards = emptyList())
        BriefStore.write(context, current)
        for (legacy in listOf(false, true)) {
            if (legacy) {
                val prefs = context.getSharedPreferences("twidget_briefs", Context.MODE_PRIVATE)
                val root = JSONObject(prefs.getString(account, null)!!)
                root.getJSONObject("lastPresentation").remove("followerFactsKey")
                prefs.edit().putString(account, root.toString()).commit()
            }
            val display = requireNotNull(BriefStore.presentation(context, account, current))
            assertEquals(strings.text(R.string.followers_net_today_week, "+4", "-25"), display.shortDescription)
            assertTrue(display.matchesFollowerFacts(current))
            assertFalse(display.summary().toString().contains("-20"))
            assertFalse(display.summary().toString().contains("moving you closer"))
            assertEquals(display, BriefStore.presentation(context, account))
        }
    }

    @Test fun liveFollowerUpdatesKeepTheOverviewCadenceWithoutMarkingNewFactsAsGenerated() = withAccount { account ->
        val now = System.currentTimeMillis()
        val completed = snapshot(account, now - 60_000).copy(
            contextFingerprint = "goal|0|false|2026-11-25|100|2026-11-26:morning|content|auto:gemini:model|stable|${strings.languageTag}",
            followers = 8074, followersToday = 4, followersWeek = -21)
        BriefStore.write(context, completed)
        val generatedAt = BriefStore.overviewGeneratedAt(context, completed)
        assertEquals(completed.aiGeneratedAt, generatedAt)
        val refreshed = completed.copy(followers = 8073, followersToday = 3, followersWeek = -22,
            sourceSyncedAt = now, analyticsCachedAt = now, generatedAt = now,
            headline = "", subheading = "", shortDescription = "", summaryGenerated = false,
            summaryGeneratedAt = 0, aiGeneratedAt = 0, providerUsed = BriefProviderUsed.TEMPLATE,
            summaryEvidenceKey = "fresh measured facts", cards = emptyList())
        repeat(4) {
            BriefStore.write(context, refreshed.copy(sourceSyncedAt = now + it))
            assertEquals("Reading live facts does not extend the generation clock", generatedAt,
                BriefStore.overviewGeneratedAt(context, refreshed))
            assertTrue(BriefGenerationCadence.isCovered(context, refreshed, now))
            assertFalse(BriefStore.read(context, account)!!.hasGeneratedNarrative)
            assertEquals(strings.text(R.string.followers_net_today_week, "+3", "-22"),
                BriefStore.presentation(context, account, refreshed)!!.shortDescription)
        }
        assertFalse(BriefGenerationCadence.isCovered(context, refreshed.copy(posts = refreshed.posts + 1), now))
        assertFalse(BriefGenerationCadence.isCovered(context, refreshed, generatedAt + BriefGenerationCadence.REUSE_MS))
        // A count may change while the model is writing. Count that completed
        // overview without persisting its obsolete measurements or prose.
        context.getSharedPreferences("twidget_briefs", Context.MODE_PRIVATE).edit().remove(account).commit()
        BriefStore.write(context, refreshed, overview = completed)
        assertTrue(BriefGenerationCadence.isCovered(context, refreshed, now))
        assertEquals(refreshed, BriefStore.read(context, account))
        assertEquals(strings.text(R.string.followers_net_today_week, "+3", "-22"),
            BriefStore.presentation(context, account, refreshed)!!.shortDescription)
    }

    @Test fun generationIsFullScreenAndRememberedHeaderReturnsAfterFailureWithoutCallingAnyModel() = withAccount { account -> runBlocking {
        val settings = context.getSharedPreferences("twidget_brief_settings", Context.MODE_PRIVATE)
        val main = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val backup = java.io.File(context.cacheDir, "brief_presentation_ui_backup.json")
        if (backup.isFile) {
            val saved = JSONObject(backup.readText())
            main.edit().putString("username", saved.getString("username")).commit()
            settings.edit().putBoolean("enabled", saved.getBoolean("enabled"))
                .putBoolean("onboarding_complete", saved.getBoolean("onboarding")).commit()
            saved.optString("fixture").takeIf { it.startsWith("brief_mem_") && it != account }?.let { TwidgetStore.removeAccount(context, it) }
            backup.delete()
        }
        val restoredEnabled = BriefSettingsStore.enabled(context)
        val restoredOnboarding = BriefSettingsStore.onboardingComplete(context)
        val originalAccount = TwidgetStore.settings(context).username
        backup.writeText(JSONObject().put("username", originalAccount).put("enabled", restoredEnabled)
            .put("onboarding", restoredOnboarding).put("fixture", account).toString())
        val lock = BriefAiCoordinator::class.java.getDeclaredField("generationMutex").apply { isAccessible = true }
            .get(BriefAiCoordinator) as Mutex
        lock.lock()
        try {
            // Prevent foreground preparation and hold generation before either provider can be called.
            BriefSettingsStore.setOnboardingComplete(context, false)
            main.edit().putString("username", account).commit()
            val success = snapshot(account)
            BriefStore.write(context, success)
            BriefStore.resetAi(context, account)
            ActivityScenario.launch<TwidgetBriefActivity>(TwidgetBriefActivity.contentIntent(context, account)).use { scenario ->
                scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                val deadline = System.currentTimeMillis() + 5_000
                var ready = false
                do {
                    scenario.onActivity { activity -> ready = activity.findViewById<TextView>(R.id.brief_summary_title).text.toString() == success.headline &&
                        activity.findViewById<View>(R.id.brief_loading).visibility == View.VISIBLE }
                    if (!ready) Thread.sleep(50)
                } while (!ready && System.currentTimeMillis() < deadline)
                assertTrue("Generation should use the full-screen loading state", ready)
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.brief_back)
                    assertTrue(toolbar.parent is com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout)
                    assertTrue(toolbar.title.isNullOrEmpty())
                    assertFalse(activity.findViewById<View>(R.id.brief_app_bar).isEnabled)
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.brief_loading).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.brief_scroll).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.brief_refresh_state).visibility)
                    assertEquals(success.subheading, activity.findViewById<TextView>(R.id.brief_summary_body).text.toString())
                    saveScreen(activity.findViewById(R.id.brief_root), "brief-full-screen-generation.png")
                    val source = BriefStore.read(context, account)!!
                    val failure = TwidgetBriefActivity::class.java.getDeclaredMethod("showProviderSetupIfRequired", BriefAiResult::class.java)
                        .apply { isAccessible = true }
                    failure.invoke(activity, BriefAiResult(source, BriefLocalStatus.AVAILABLE))
                    assertEquals(success.headline, activity.findViewById<TextView>(R.id.brief_summary_title).text.toString())
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.brief_refresh_error).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.brief_provider_required).visibility)
                    assertFalse(BriefStore.read(context, account)!!.hasGeneratedNarrative)
                    saveScreen(activity.findViewById(R.id.brief_root), "brief-retained-failure.png")
                }
                instrumentation.waitForIdleSync(); Thread.sleep(400)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    java.io.File(context.getExternalFilesDir(null), "brief-native-floating-main.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
        } finally {
            BriefLaunchGeneration.current(account)?.cancel()
            settings.edit().putBoolean("enabled", restoredEnabled).putBoolean("onboarding_complete", restoredOnboarding).commit()
            main.edit().putString("username", originalAccount).commit()
            lock.unlock()
            backup.delete()
        }
    } }

    @Test fun memoryIsAccountScopedHonorsContentChangesAndFollowsHandleMigration() = withAccount { old -> withAccount { next ->
        val success = snapshot(old)
        BriefStore.write(context, success)
        assertNull(BriefStore.presentation(context, next))
        com.tjg.twidget.data.AccountHandleMigration.copyCompanionData(context, old, next)
        assertEquals(success.headline, BriefStore.presentation(context, next)!!.title)
        val changed = BriefStore.presentation(context, next)!!.copy(contentFingerprint = "disabled categories")
        assertFalse(changed.matches(strings, BriefSettingsStore.contentFingerprint(context)))
        TwidgetStore.removeAccount(context, old)
        assertNull(BriefStore.presentation(context, old))
        assertNotNull(BriefStore.presentation(context, next))
        TwidgetStore.removeAccount(context, next)
        assertNull(BriefStore.presentation(context, next))
    } }

    private fun saveScreen(view: View, filename: String) {
        // Visibility changes request a new layout; render that layout rather than the previous frame.
        val width = view.width
        val height = view.height
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File(context.getExternalFilesDir(null), filename).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun withAccount(block: (String) -> Unit) {
        val account = "brief_mem_${System.nanoTime().toString().takeLast(8)}"
        TwidgetStore.addAccount(context, account)
        try { block(account) } finally { TwidgetStore.removeAccount(context, account) }
    }
}
