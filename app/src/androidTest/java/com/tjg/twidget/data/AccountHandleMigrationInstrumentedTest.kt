package com.tjg.twidget.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountHandleMigrationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val old = "audit_rename_a"
    private val next = "audit_rename_b"
    private fun profile(handle: String, id: String = "123456789", time: Long = System.currentTimeMillis()) =
        ProfileStats("Rename audit", handle, 250, 20, 30, 40, userId = id, syncedAt = time)

    @Test fun sameIdentityMovesHistoryDefaultWidgetsGoalsAndImportsAndRejectsLateResponses() {
        val original = TwidgetStore.settings(context)
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val schedules = com.tjg.twidget.schedule.ScheduleStore(context)
        val local = com.tjg.twidget.schedule.ScheduledPost(
            id = "audit_handle_local", provider = com.tjg.twidget.schedule.ScheduleProvider.LOCAL_REMINDER,
            accountId = old, accountUsername = old, scheduledAt = null, thread = emptyList(),
        )
        val remote = local.copy(id = "audit_handle_remote", provider = com.tjg.twidget.schedule.ScheduleProvider.BUFFER,
            accountId = "remote-channel-42", deletedAt = System.currentTimeMillis())
        val companions = listOf("twidget_account_goals", "twidget_imported_analytics", "twidget_daily_streak", "twidget_bangers")
        try {
            val now = System.currentTimeMillis()
            TwidgetStore.addAccount(context, old)
            val past = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -10) }.timeInMillis
            TwidgetStore.saveStats(context, profile(old, time = now - 1000).copy(
                history = listOf(HistorySample("Past", 123, 0, 0, 0, timestamp = past, imported = true))))
            TwidgetStore.saveSettings(context, original.copy(username = old))
            // Measure the real account after setup. Before selecting an account,
            // fullHistory also contains unpersisted onboarding demo samples.
            val before = TwidgetStore.fullHistory(context, old)
            prefs.edit().putString("widget_account_987654", old).putString("chart_range_${old}_followers", "ALL_TIME")
                .putLong("milestone_target_$old", 1000L).commit()
            companions.forEach { name ->
                val key = when (name) { "twidget_account_goals" -> "${old}_goals"; "twidget_bangers" -> "latest:$old"; else -> old }
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString(key, "[]").commit()
            }
            schedules.create(local)
            schedules.create(remote)
            val generation = TwidgetStore.accountGeneration(context, old)
            TwidgetStore.saveStats(context, profile(next, time = now).copy(requestedUsername = old, accountGeneration = generation))
            assertFalse(TwidgetStore.accounts(context).contains(old))
            assertTrue(TwidgetStore.accounts(context).contains(next))
            assertEquals(next, TwidgetStore.settings(context).username)
            assertEquals(next, prefs.getString("widget_account_987654", ""))
            assertEquals("ALL_TIME", prefs.getString("chart_range_${next}_followers", ""))
            assertEquals(1000L, prefs.getLong("milestone_target_$next", 0L))
            assertEquals("123456789", TwidgetStore.currentStats(context, next).userId)
            assertEquals(next, schedules.get(local.id)?.accountUsername)
            assertEquals(next, schedules.get(local.id)?.accountId)
            assertEquals(next, schedules.get(remote.id)?.accountUsername)
            assertEquals("remote-channel-42", schedules.get(remote.id)?.accountId)
            assertEquals(remote.deletedAt, schedules.get(remote.id)?.deletedAt)
            assertTrue(TwidgetStore.fullHistory(context, next).any { it.followers == 123L && it.imported })
            assertTrue(TwidgetStore.fullHistory(context, next).size >= before.size)
            companions.forEach { name ->
                val key = when (name) { "twidget_account_goals" -> "${next}_goals"; "twidget_bangers" -> "latest:$next"; else -> next }
                assertEquals(name, "[]", context.getSharedPreferences(name, Context.MODE_PRIVATE).getString(key, null))
            }
            TwidgetStore.saveStats(context, profile(old, time = now + 1000).copy(accountGeneration = generation, requestedUsername = old))
            assertFalse(TwidgetStore.accounts(context).contains(old))
            assertEquals(250L, TwidgetStore.currentStats(context, next).followersCount)
        } finally {
            schedules.removeAll(setOf(local.id, remote.id))
            TwidgetStore.saveSettings(context, original)
            listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) }
            prefs.edit().remove("widget_account_987654").remove("chart_range_${next}_followers")
                .remove("milestone_target_$next").commit()
            companions.forEach { name ->
                val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val edit = store.edit()
                store.all.keys.filter { it.contains(old) || it.contains(next) }.forEach(edit::remove)
                edit.commit()
            }
        }
    }

    @Test fun legacyRenameRequiresExplicitIntentAndKnownIdentityCannotBeReassigned() {
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old, id = ""))
            val generation = TwidgetStore.accountGeneration(context, old)
            assertFails { TwidgetStore.migrateAccount(context, old, profile(next), generation) }
            TwidgetStore.migrateAccount(context, old, profile(next), generation, allowLegacy = true)
            assertTrue(TwidgetStore.accounts(context).contains(next))
            assertFails {
                TwidgetStore.migrateAccount(context, next, profile(old, id = "999"),
                    TwidgetStore.accountGeneration(context, next), allowLegacy = true)
            }
            assertTrue(TwidgetStore.accounts(context).contains(next))
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
    }

    @Test fun addingNewHandleWithSameIdMigratesButExistingDestinationAndRemovedRequestsAreProtected() {
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            TwidgetStore.addAccount(context, next)
            TwidgetStore.saveStats(context, profile(next, id = "999"))
            assertFails { TwidgetStore.migrateAccount(context, old, profile(next), TwidgetStore.accountGeneration(context, old)) }
            TwidgetStore.removeAccount(context, next)
            TwidgetStore.addAccount(context, next)
            // A stale request from a removed/re-added source cannot rename the replacement.
            val generation = TwidgetStore.accountGeneration(context, old)
            TwidgetStore.removeAccount(context, old)
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(next).copy(requestedUsername = old, accountGeneration = generation))
            assertEquals("", TwidgetStore.currentStats(context, next).userId)
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            // Onboarding creates the destination placeholder before saving the live profile.
            TwidgetStore.addAccount(context, next)
            TwidgetStore.saveStats(context, profile(next))
            assertFalse(TwidgetStore.accounts(context).contains(old))
            assertEquals("123456789", TwidgetStore.currentStats(context, next).userId)
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
    }

    @Test fun renamingDoesNotCompactExistingArchiveObservations() {
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            prefs.edit().putString("history_$old", """[
                {"dayLabel":"Jan 1","followers":100,"ts":1577836800000},
                {"dayLabel":"Jan 2","followers":101,"ts":1577923200000}
            ]""").commit()
            TwidgetStore.saveStats(context, profile(next).copy(
                requestedUsername = old, accountGeneration = TwidgetStore.accountGeneration(context, old)))
            val migrated = org.json.JSONArray(prefs.getString("history_$next", "[]"))
            assertEquals(3, migrated.length())
            assertEquals(100L, migrated.getJSONObject(0).getLong("followers"))
            assertEquals(101L, migrated.getJSONObject(1).getLong("followers"))
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
    }

    @After fun flushCleanupBeforeInstrumentationStopsTheApp() {
        // Instrumentation can kill the process before SharedPreferences.apply reaches disk.
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("audit_handle_cleanup", true).commit()
        context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
            .remove("audit_handle_cleanup").commit()
    }

    private var originalSharing = false
    private fun clearFixtureStorage() {
        AccountHandleMigration.checkpoint = null
        TwidgetStore.recoverAccountMigration(context)
        listOf(old, next).forEach {
            TwidgetStore.removeAccount(context, it)
            AccountHandleMigration.removeSourceData(context, it)
        }
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.contains("audit_rename") }.forEach(edit::remove)
        edit.commit()
    }

    @Before fun isolateNetworkAndRemoveFixtureAliases() {
        clearFixtureStorage()
        val settings = TwidgetStore.settings(context)
        originalSharing = settings.shareHistory
        TwidgetStore.saveSettings(context, settings.copy(shareHistory = false))
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(AccountHandleMigration.REDIRECT_PREFIX) && it.contains("audit_rename") }.forEach(edit::remove)
        edit.commit()
    }

    @After fun restoreSharingAndCleanJournalFixtures() {
        clearFixtureStorage()
        TwidgetStore.saveSettings(context, TwidgetStore.settings(context).copy(shareHistory = originalSharing))
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(AccountHandleMigration.REDIRECT_PREFIX) && it.contains("audit_rename") }.forEach(edit::remove)
        edit.commit()
    }

    @Test fun interruptedMigrationRecoversAllStoresAndSchedulesAtEveryBoundary() {
        listOf("companions", "published", "cleanup").forEach { boundary ->
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            val raw = context.getSharedPreferences("twidget_daily_streak", Context.MODE_PRIVATE)
            raw.edit().putString(old, "[\"2026-10-10\"]").commit()
            val schedules = com.tjg.twidget.schedule.ScheduleStore(context)
            val post = com.tjg.twidget.schedule.ScheduledPost(id = "audit_recover_post",
                provider = com.tjg.twidget.schedule.ScheduleProvider.LOCAL_REMINDER,
                accountUsername = old, accountId = old, scheduledAt = null, thread = emptyList())
            schedules.upsert(post)
            try {
                AccountHandleMigration.checkpoint = { if (it == boundary) error("Simulated process death") }
                try {
                    TwidgetStore.migrateAccount(context, old, profile(next), TwidgetStore.accountGeneration(context, old))
                    fail("Expected interruption")
                } catch (_: IllegalStateException) { }
                AccountHandleMigration.checkpoint = null
                TwidgetStore.recoverAccountMigration(context)
                TwidgetStore.recoverAccountMigration(context)
                assertEquals("[\"2026-10-10\"]", raw.getString(next, null))
                assertFalse(raw.contains(old))
                assertEquals(next, schedules.get(post.id)?.accountUsername)
                assertTrue(TwidgetStore.accounts(context).contains(next))
                assertFalse(context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).contains(AccountHandleMigration.JOURNAL))
            } finally {
                AccountHandleMigration.checkpoint = null
                TwidgetStore.recoverAccountMigration(context)
                schedules.remove(post.id)
                listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) }
                raw.edit().remove(old).remove(next).commit()
                context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).edit()
                    .remove(AccountHandleMigration.REDIRECT_PREFIX + old).remove(AccountHandleMigration.REDIRECT_PREFIX + next).commit()
            }
        }
    }

    @Test fun concurrentStreakUpdatesAndQueuedWritesFollowMigrationWithoutLoss() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            DailyStreakStore.mergeOriginalActivity(context, old, setOf(1791633600000L), 1, 1, true)
            val queued = AccountPreferences.open(context, "twidget_daily_streak").edit().putLong("${old}_checked_at", 77L)
            val waiting = java.util.concurrent.CountDownLatch(1)
            lateinit var update: java.util.concurrent.Future<Set<String>>
            synchronized(TwidgetStore) {
                update = pool.submit<Set<String>> {
                    waiting.countDown()
                    DailyStreakStore.mergeOriginalActivity(context, old, setOf(1791720000000L), 2, 2, true)
                }
                assertTrue(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS))
                TwidgetStore.migrateAccount(context, old, profile(next), TwidgetStore.accountGeneration(context, old))
            }
            assertEquals(2, update.get(5, java.util.concurrent.TimeUnit.SECONDS).size)
            queued.commit()
            assertEquals(2, DailyStreakStore.activeDays(context, next).size)
            assertEquals(77L, context.getSharedPreferences("twidget_daily_streak", Context.MODE_PRIVATE).getLong("${next}_checked_at", 0))
            assertFalse(context.getSharedPreferences("twidget_daily_streak", Context.MODE_PRIVATE).contains(old))
            // A late deletion of the old handle must not clear the new account's data.
            TwidgetStore.removeAccount(context, old)
            assertEquals(2, DailyStreakStore.activeDays(context, next).size)
        } finally {
            pool.shutdownNow()
            listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) }
            val raw = context.getSharedPreferences("twidget_daily_streak", Context.MODE_PRIVATE)
            val edit = raw.edit()
            raw.all.keys.filter { it.startsWith(old) || it.startsWith(next) }.forEach(edit::remove)
            edit.commit()
        }
    }

    @Test fun lateImportsMergeIntoTheNewHandleAndFollowerArchivesMoveWithIt() {
        val date = java.time.LocalDate.now().minusDays(3)
        val imported = com.tjg.twidget.analytics.ImportedAnalyticsStore
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            imported.saveVerified(context, old, listOf(com.tjg.twidget.analytics.XAnalyticsMovement(date, 1, 0, impressions = 10)))
            com.tjg.twidget.followers.TopFollowersArchiveStore.append(context, old, listOf(
                com.tjg.twidget.followers.TopFollower(id = "1", username = "example", name = "Example", followers = 20, verified = false, avatarUrl = "")
            ), 1)
            TwidgetStore.migrateAccount(context, old, profile(next), TwidgetStore.accountGeneration(context, old))
            imported.saveVerified(context, old, listOf(com.tjg.twidget.analytics.XAnalyticsMovement(date.plusDays(1), 2, 0, impressions = 20)))
            assertEquals(listOf(10L, 20L), imported.all(context, next).map { it.impressions })
            assertEquals(1, com.tjg.twidget.followers.TopFollowersArchiveStore.readAll(context, next).size)
            assertFalse(java.io.File(context.filesDir, "top_followers/$old.jsonl").exists())
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
    }

    @Test fun malformedOptionalCacheCannotStrandTheRecoveryJournal() {
        val raw = context.getSharedPreferences("twidget_analytics", Context.MODE_PRIVATE)
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            raw.edit().putString("v6:fxtwitter:$old", "incomplete cache").commit()
            TwidgetStore.migrateAccount(context, old, profile(next), TwidgetStore.accountGeneration(context, old))
            assertEquals("incomplete cache", raw.getString("v6:fxtwitter:$next", null))
            assertTrue(TwidgetStore.accounts(context).contains(next))
            assertFalse(context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).contains(AccountHandleMigration.JOURNAL))
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
    }

    @Test fun staleNetworkResultsCannotOverwriteTheRenamedAccount() {
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            val generation = TwidgetStore.accountGeneration(context, old)
            TwidgetStore.migrateAccount(context, old, profile(next), generation)
            var wrote = false
            try {
                TwidgetStore.withAccountGeneration(context, old, generation) { wrote = true }
                fail("Expected a stale network result to be rejected")
            } catch (_: IllegalStateException) { }
            assertFalse(wrote)
            TwidgetStore.withAccountGeneration(context, next, TwidgetStore.accountGeneration(context, next)) { wrote = true }
            assertTrue(wrote)
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
    }

    @Test fun leftoverDestinationGoalsRejectMigrationWithoutChangingEitherAccount() {
        val goals = context.getSharedPreferences("twidget_account_goals", Context.MODE_PRIVATE)
        try {
            TwidgetStore.addAccount(context, old)
            TwidgetStore.saveStats(context, profile(old))
            goals.edit().putString("${next}_goals", "[]").putBoolean("${next}_configured", true).commit()
            assertFails { TwidgetStore.migrateAccount(context, old, profile(next), TwidgetStore.accountGeneration(context, old)) }
            assertTrue(TwidgetStore.accounts(context).contains(old))
            assertFalse(TwidgetStore.accounts(context).contains(next))
            assertTrue(goals.getBoolean("${next}_configured", false))
            assertFalse(context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE).contains(AccountHandleMigration.JOURNAL))
        } finally {
            TwidgetStore.removeAccount(context, old)
            goals.edit().remove("${next}_goals").remove("${next}_configured").commit()
        }
    }

    @Test fun unverifiedHistoryCannotOverwriteMigratedObservations() {
        try {
            TwidgetStore.addAccount(context, old)
            val sample = HistorySample("Past", 100, 2, 3, 4, timestamp = System.currentTimeMillis() - 3 * 86400000L)
            TwidgetStore.saveStats(context, profile(old).copy(history = listOf(sample)))
            val incoming = profile(next).copy(history = listOf(sample.copy(followers = 999)), historyUserId = "999")
            TwidgetStore.migrateAccount(context, old, incoming, TwidgetStore.accountGeneration(context, old))
            assertTrue(TwidgetStore.fullHistory(context, next).any { it.followers == 100L })
            assertFalse(TwidgetStore.fullHistory(context, next).any { it.followers == 999L })
        } finally { listOf(old, next).forEach { TwidgetStore.removeAccount(context, it) } }
    }

    private fun assertFails(action: () -> Unit) {
        try { action(); fail("Expected identity/conflict validation to reject the migration") }
        catch (_: IllegalArgumentException) { }
    }
}
