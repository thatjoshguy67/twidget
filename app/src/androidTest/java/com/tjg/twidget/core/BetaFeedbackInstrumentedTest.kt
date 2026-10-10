package com.tjg.twidget.core

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.XAnalyticsMovement
import com.tjg.twidget.brief.BriefEditorialSummary
import com.tjg.twidget.brief.BriefSnapshot
import com.tjg.twidget.brief.BriefStrings
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollower
import com.tjg.twidget.followers.TopFollowersArchiveStore
import com.tjg.twidget.followers.TopFollowersState
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.schedule.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BetaFeedbackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun removingAnAccountClearsImportedAnalyticsAndBothFollowerCaches() {
        val username = "beta_cleanup_test"
        val survivor = "beta_cleanup_survivor"
        val savedSettings = TwidgetStore.settings(context)
        val follower = TopFollower("1", "follower", "Follower", 42, false, "")
        try {
            for (account in listOf(username, survivor)) {
                TwidgetStore.addAccount(context, account)
                ImportedAnalyticsStore.saveVerified(context, account,
                    listOf(XAnalyticsMovement(LocalDate.now(), 1, 0, impressions = 100)))
                TopFollowersStore.write(context, account, TopFollowersState(top = listOf(follower)))
                TopFollowersArchiveStore.append(context, account, listOf(follower), 1)
            }
            TopFollowersArchiveStore.beginReplacement(context, username)
            TopFollowersArchiveStore.appendReplacement(context, username, listOf(follower), 1)
            TwidgetStore.removeAccount(context, "@BETA_CLEANUP_TEST")
            // A pending download must not bring a deleted cache back when it commits.
            TopFollowersArchiveStore.commitReplacement(context, username)
            TwidgetStore.addAccount(context, username)
            assertTrue(ImportedAnalyticsStore.all(context, username).isEmpty())
            assertTrue(TopFollowersStore.read(context, username).top.isEmpty())
            assertTrue(TopFollowersArchiveStore.readAll(context, username).isEmpty())
            assertEquals(1, ImportedAnalyticsStore.all(context, survivor).size)
            assertEquals(1, TopFollowersStore.read(context, survivor).top.size)
            assertEquals(1, TopFollowersArchiveStore.readAll(context, survivor).size)
        } finally {
            TwidgetStore.removeAccount(context, username)
            TwidgetStore.removeAccount(context, survivor)
            TwidgetStore.saveSettings(context, savedSettings)
        }
    }

    @Test fun bufferSyncKeepsTrashedDraftsHiddenAndRestoresThemOnlyOnRequest() {
        val store = ScheduleStore(context)
        val now = System.currentTimeMillis()
        val post = ScheduledPost(provider = ScheduleProvider.BUFFER,
            status = ScheduleStatus.DRAFT, accountUsername = "beta-channel", scheduledAt = null,
            thread = listOf(ScheduleThreadItem(text = "Draft")), remotePostId = "beta-remote")
        val remote = BufferPost("beta-remote", "beta-channel", "Draft", "draft", null, now)
        val sync = BufferScheduleSync(context, store)
        store.create(post)
        try {
            store.moveToTrash(post.id, now)
            val result = sync.reconcileRemotePosts("beta-channel", "beta-account", listOf(remote), now)
            assertEquals(0, result.imported)
            assertEquals(0, result.updated)
            assertFalse(store.list().any { it.id == post.id })
            assertEquals(now, store.listTrash().single { it.id == post.id }.deletedAt)
            val duplicateText = remote.copy(id = "beta-new-remote")
            val imported = sync.reconcileRemotePosts("beta-channel", "beta-account", listOf(remote, duplicateText), now)
            assertEquals(1, imported.imported)
            assertTrue(store.list().any { it.remotePostId == duplicateText.id })
            assertFalse(store.list().any { it.id == post.id })
            sync.reconcileRemotePosts("beta-channel", "beta-account", emptyList(), now)
            assertTrue(store.listTrash().any { it.id == post.id })
            store.restoreFromTrash(post.id, now + 1)
            sync.reconcileRemotePosts("beta-channel", "beta-account", listOf(remote), now + 2)
            assertEquals("Draft", store.list().single { it.id == post.id }.thread.first().text)
        } finally {
            store.remove(post.id)
            store.remove(BufferScheduleSync.remoteLocalId("beta-new-remote"))
        }
    }

    @Test fun briefWidgetLanguageOverrideUsesGermanResourcesWithoutChangingEnglishCache() {
        val snapshot = BriefSnapshot("test", 1, 1, 0, 0, 100, 0, 0, 0, 0,
            cards = emptyList(), topFollowerRanks = emptyMap(), headline = "English headline",
            subheading = "English body", language = "en")
        val german = BriefStrings.from(context, "de")
        val summary = BriefEditorialSummary.from(snapshot, german)
        assertEquals(AppLocales.wrap(context, "de").getString(R.string.brief_summary_title_default), summary.title)
        assertFalse(summary.body.contains("English"))
        assertEquals("English headline", BriefEditorialSummary.from(snapshot, BriefStrings.from(context, "en")).title)
    }

    @Test fun onboardingFitsNarrowAndShortWindowsWithLargeGermanText() {
        instrumentation.runOnMainSync {
            for (widthDp in listOf(240, 360, 800)) {
                val config = Configuration(context.resources.configuration).apply {
                    fontScale = 1.5f
                    setLocale(java.util.Locale.GERMAN)
                }
                val themed = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Twidget)
                val root = LayoutInflater.from(themed).inflate(R.layout.activity_brief_onboarding, null)
                val title = root.findViewById<TextView>(R.id.brief_onboarding_title)
                title.text = "Dein persönliches Briefing für einen sehr langen Namen"
                val density = themed.resources.displayMetrics.density
                val width = (widthDp * density).toInt()
                val height = (260 * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val scroll = (root as android.view.ViewGroup).getChildAt(0) as ScrollView
                val back = root.findViewById<View>(R.id.brief_onboarding_back)
                val button = root.findViewById<TextView>(R.id.brief_onboarding_continue)
                assertTrue(back.bottom <= scroll.top)
                assertTrue(button.width > 0)
                assertTrue(button.width <= scroll.width - (56 * density).toInt())
                assertTrue(button.height >= button.layout.height + button.paddingTop + button.paddingBottom)
                scroll.scrollTo(0, scroll.getChildAt(0).height)
                val bounds = android.graphics.Rect()
                assertTrue(button.getLocalVisibleRect(bounds))
                assertEquals(button.height, bounds.height())
                var pressed = false
                back.setOnClickListener { pressed = true }
                back.performClick()
                assertTrue(pressed)
            }
        }
    }
    @Test fun onboardingContinueShadowExtendsBelowTheButton() {
        androidx.test.core.app.ActivityScenario.launch(com.tjg.twidget.settings.SettingsActivity::class.java).use { scenario ->
            lateinit var button: TextView
            scenario.onActivity { activity ->
                activity.setContentView(R.layout.activity_brief_onboarding)
                activity.findViewById<TextView>(R.id.brief_onboarding_title).text =
                    activity.getString(R.string.brief_onboarding_title, "That Josh Guy")
                button = activity.findViewById(R.id.brief_onboarding_continue)
            }
            instrumentation.waitForIdleSync()
            // Elevation shadows are produced by the hardware renderer, not View.draw(Canvas).
            android.os.SystemClock.sleep(300)
            val bounds = android.graphics.Rect()
            instrumentation.runOnMainSync {
                val location = IntArray(2)
                button.getLocationOnScreen(location)
                bounds.set(location[0], location[1], location[0] + button.width, location[1] + button.height)
            }
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val sampleY = bounds.bottom + (2 * context.resources.displayMetrics.density).toInt()
            val shadow = screenshot.getPixel(bounds.centerX(), sampleY)
            val background = screenshot.getPixel(8, sampleY)
            fun brightness(color: Int) = android.graphics.Color.red(color) +
                android.graphics.Color.green(color) + android.graphics.Color.blue(color)
            screenshot.recycle()
            assertTrue("The button's shadow must extend below its parent boundary: shadow=$shadow background=$background",
                brightness(shadow) < brightness(background) - 3)
        }
    }

}
