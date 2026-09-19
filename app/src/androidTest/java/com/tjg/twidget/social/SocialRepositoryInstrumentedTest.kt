package com.tjg.twidget.social

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocialRepositoryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val databaseName = "social-test-$suffix.db"
    private val prefsName = "social-test-$suffix"
    private lateinit var repository: SocialRepository
    private val isolatedContext = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(if (name == TwidgetStore.PREFS) prefsName else name, mode)
    }

    @Before fun setUp() { repository = SocialRepository(context, databaseName) }
    @After fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(prefsName)
    }

    private fun snapshot(followers: Long = 12): LegacySocialSnapshot = LegacySocialSnapshot(
        listOf(LegacySocialAccount("Name", ProfileStats("Original", "Name", followers, 0, 4, 0,
            syncedAt = 200, followingKnown = false, likesKnown = false),
            listOf(HistorySample("Sep 18", 10, 0, 4, 0, 100, followingKnown = false,
                likesKnown = false, imported = true, sharedImport = true)))),
        "Name", mapOf(5 to "Name", 6 to ""), true,
    )

    @Test fun migrationPersistsAcrossReopenAndDoesNotDuplicateHistory() {
        val first = repository.synchronizeLegacy(snapshot())
        assertEquals(first, repository.synchronizeLegacy(snapshot()))
        val id = first.accounts.single().id
        assertEquals(8, repository.observations(id).size)
        assertNull(repository.observations(id).first { it.metric == SocialMetric.FOLLOWING }.value)
        assertTrue(repository.observations(id).first { it.observedAt == 100L }.sharedImport)
        assertTrue(repository.needsUpgradeIntroduction())
        repository.close()
        repository = SocialRepository(context, databaseName)
        assertEquals(first, repository.catalog())
        repository.completeUpgradeIntroduction()
        repository.synchronizeLegacy(snapshot(15))
        assertFalse(repository.needsUpgradeIntroduction())
        assertEquals(15L, repository.observations(id).last { it.metric == SocialMetric.FOLLOWERS }.value)
    }

    @Test fun legacyRefreshPreservesLinkedProfileDisplayAndNewWidgetBinding() {
        val migrated = repository.synchronizeLegacy(snapshot())
        val x = migrated.accounts.single().id
        val github = PlatformAccount("gh", SocialPlatform.GITHUB, "123", "name", "GitHub Name", "https://example.com/avatar.png")
        repository.edit { SocialProfilePolicy.add(it, github) }
        val state = repository.edit { catalog ->
            val linked = SocialProfilePolicy.link(catalog, migrated.defaultProfileId!!, setOf(catalog.profileFor("gh")!!.id))
            SocialProfilePolicy.display(linked, migrated.defaultProfileId, "gh", x, "Chosen name")
                .copy(widgets = linked.widgets.map { if (it.widgetId == 5) it.copy(accountId = "gh") else it })
        }
        val refreshed = repository.synchronizeLegacy(snapshot(99))
        assertEquals(state.profiles, refreshed.profiles)
        assertEquals("gh", refreshed.widgets.first { it.widgetId == 5 }.accountId)
        assertEquals(99L, repository.observations(x).last { it.metric == SocialMetric.FOLLOWERS }.value)
    }

    @Test fun failedEditRollsBackAndRemovingAccountRejectsLateRefresh() {
        val original = repository.synchronizeLegacy(snapshot())
        val id = original.accounts.single().id
        val saved = repository.observations(id)
        assertThrows(IllegalArgumentException::class.java) {
            repository.edit { it.copy(profiles = emptyList()) }
        }
        assertEquals(original, repository.catalog())
        assertEquals(saved, repository.observations(id))
        val removed = repository.edit { SocialProfilePolicy.remove(it, id) }
        assertNull(removed.widgets.first().accountId)
        assertTrue(repository.observations(id).isEmpty())
        assertFalse(repository.recordObservations(id, saved))
        assertTrue(repository.catalog().accounts.isEmpty())
    }

    @Test fun legacyRemovalKeepsOtherPlatformAndRepairsProfileReferences() {
        repository.synchronizeLegacy(snapshot())
        repository.edit { SocialProfilePolicy.add(it, PlatformAccount("gh", SocialPlatform.GITHUB, "1", "name", "GitHub")) }
        repository.edit { SocialProfilePolicy.link(it, it.profiles.first().id, setOf(it.profiles.last().id)) }
        val next = repository.synchronizeLegacy(LegacySocialSnapshot(emptyList(), "", emptyMap(), true))
        assertEquals("gh", next.accounts.single().id)
        assertEquals("gh", next.profiles.single().nameAccountId)
        assertTrue(repository.observations(LegacySocialMigration.accountId("Name")).isEmpty())
        val restored = repository.synchronizeLegacy(snapshot())
        assertEquals(2, restored.profiles.size)
        assertEquals(2, restored.profiles.map { it.id }.distinct().size)
        assertEquals(next.profiles.single(), restored.profileFor("gh"))
        assertFalse(restored.profileFor(LegacySocialMigration.accountId("Name"))!!.linked)
    }

    @Test fun updatingProfileDoesNotCascadeDeleteObservations() {
        val original = repository.synchronizeLegacy(snapshot())
        val account = original.accounts.single()
        val saved = repository.observations(account.id)
        repository.edit { SocialProfilePolicy.display(it, it.defaultProfileId!!, account.id, account.id, "Custom") }
        assertEquals(saved, repository.observations(account.id))
    }

    @Test fun rawLegacySnapshotIncludesOrphanWidgetWithoutWritingOrSeedingDemoData() {
        val preferences = isolatedContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val stats = JSONObject().put("fullName", "Name").put("userName", "Name")
            .put("followersCount", 0).put("followersKnown", true).put("syncedAt", 100)
        preferences.edit().putString("accounts", JSONArray(listOf("Name", "@NAME")).toString())
            .putString("username", "Name").putString("profile", stats.toString())
            .putString("widget_account_5", "orphan").putString("widget_account_6", "")
            .putBoolean("share_history", true).putString("milestone_target_name", "retained").commit()
        val before = preferences.all
        val legacy = TwidgetStore.socialMigrationSnapshot(isolatedContext)
        assertEquals(listOf("Name", "orphan"), legacy.accounts.map { it.handle })
        assertTrue(legacy.accounts.all { it.history.isEmpty() })
        assertNull(legacy.accounts.last().stats)
        val migrated = repository.synchronizeLegacy(legacy)
        assertEquals(2, migrated.accounts.size)
        assertEquals(LegacySocialMigration.accountId("orphan"), migrated.widgets.first { it.widgetId == 5 }.accountId)
        assertEquals(before, preferences.all)
        assertEquals(0L, repository.observations(LegacySocialMigration.accountId("Name"))
            .first { it.metric == SocialMetric.FOLLOWERS }.value)
    }

    @Test fun freshInstallHasNoAccountsOrUpgradeIntroduction() {
        val legacy = TwidgetStore.socialMigrationSnapshot(isolatedContext)
        assertEquals(SocialCatalog(), repository.synchronizeLegacy(legacy))
        assertFalse(repository.needsUpgradeIntroduction())
        // Finishing ordinary first-run onboarding later must not trigger the upgrade introduction.
        repository.synchronizeLegacy(snapshot())
        assertFalse(repository.needsUpgradeIntroduction())
    }

    @Test fun corruptLegacyPayloadLeavesTheExistingDatabaseAndPreferencesIntact() {
        val original = repository.synchronizeLegacy(snapshot())
        val preferences = isolatedContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        preferences.edit().putString("accounts", "not-json").commit()
        assertThrows(org.json.JSONException::class.java) { TwidgetStore.socialMigrationSnapshot(isolatedContext) }
        assertEquals(original, repository.catalog())
        assertEquals("not-json", preferences.getString("accounts", null))
    }

    @Test fun interruptedMigrationRollsBackRowsAndMarkerThenRetriesSuccessfully() {
        repository.catalog() // Create the schema before simulating a database write failure.
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use {
            it.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON observations BEGIN SELECT RAISE(ABORT, 'simulated failure'); END")
        }
        assertThrows(RuntimeException::class.java) { repository.synchronizeLegacy(snapshot()) }
        assertEquals(SocialCatalog(), repository.catalog())
        assertFalse(repository.needsUpgradeIntroduction())
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { it.execSQL("DROP TRIGGER fail_import") }
        val migrated = repository.synchronizeLegacy(snapshot())
        assertEquals(1, migrated.accounts.size)
        assertEquals(8, repository.observations(migrated.accounts.single().id).size)
        assertTrue(repository.needsUpgradeIntroduction())
    }

    @Test fun reconnectionMapsProviderObservationsToTheOriginalLocalId() {
        val account = PlatformAccount("first", SocialPlatform.BLUESKY, "did:plc:123", "name.bsky.social", "Name")
        fun response(id: String, value: Long) = SocialProfileResult.Success(account.copy(id = id), listOf(
            MetricObservation(id, SocialMetric.FOLLOWERS, value, 100, "bluesky_public")))
        repository.connect(response("first", 10))
        val reconnected = repository.connect(response("second", 20))
        assertEquals("first", reconnected.id)
        assertEquals(1, repository.catalog().accounts.size)
        assertEquals(20L, repository.observations("first").single().value)
        assertTrue(repository.observations("second").isEmpty())
        repository.edit { SocialProfilePolicy.remove(it, "first") }
        assertFalse(repository.applyRefresh(response("first", 30)))
        assertTrue(repository.catalog().accounts.isEmpty())
    }

    @Test fun dateLabelOnlyLegacyHistoryIsRetainedWithoutMetricBackfilling() {
        isolatedContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putString("username", "name")
            .putString("history_name", """[{"dayLabel":"Jan 1","followers":15}]""").commit()
        val snapshot = TwidgetStore.socialMigrationSnapshot(isolatedContext)
        assertTrue(snapshot.accounts.single().history.single().timestamp > 0)
        repository.synchronizeLegacy(snapshot)
        val observations = repository.observations(LegacySocialMigration.accountId("name"))
        assertEquals(15L, observations.first { it.metric == SocialMetric.FOLLOWERS }.value)
        assertNull(observations.first { it.metric == SocialMetric.FOLLOWING }.value)
    }
}
