package com.tjg.twidget.followers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tjg.twidget.R
import com.tjg.twidget.bridge.BridgeLog
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.MainActivity
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopFollowersBridgeOnlyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun manifestHasNoForegroundService() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        assertFalse(info.requestedPermissions.orEmpty().any { it.startsWith("android.permission.FOREGROUND_SERVICE") })
        assertFalse(info.services.orEmpty().any { it.name == "androidx.work.impl.foreground.SystemForegroundService" })
    }

    @Test fun upgradeCancelsOldWorkAndPreservesRankingsAndConsent() {
        TopFollowersLocalScanCleanup.run(context)
        val username = "bridge_migration_test"
        val consent = TwidgetStore.settings(context).shareHistory
        val original = TopFollowersStore.read(context, username)
        val ranked = TopFollower("42", "cached", "Cached follower", 1000, false, "")
        val manager = WorkManager.getInstance(context)
        val pending = OneTimeWorkRequestBuilder<TopFollowersScanWorker>()
            .setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueue(pending).result.get()
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("top_followers_scan_progress", "Legacy scan", NotificationManager.IMPORTANCE_LOW))
        try {
            TopFollowersStore.write(context, username, TopFollowersState(
                top = listOf(ranked), complete = true, completedAt = 123L,
                scanning = true, cursor = "old-cursor", activeRunId = "old-run"))
            context.getSharedPreferences("twidget_top_followers_migrations", Context.MODE_PRIVATE)
                .edit().remove("bridge_only_v2").commit()
            TopFollowersLocalScanCleanup.run(context)
            val result = TopFollowersStore.read(context, username)
            assertEquals(listOf(ranked), result.top)
            assertEquals(123L, result.completedAt)
            assertTrue(result.complete)
            assertFalse(result.scanning)
            assertEquals("", result.cursor)
            assertEquals("", result.activeRunId)
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(pending.id).get()!!.state)
            assertNull(notifications.getNotificationChannel("top_followers_scan_progress"))
            assertEquals(consent, TwidgetStore.settings(context).shareHistory)
        } finally {
            TopFollowersStore.write(context, username, original)
        }
    }

    @Test fun scanRequestWithoutSharedHistoryDoesNotEnqueueWork() {
        val saved = TwidgetStore.settings(context)
        val username = "bridge_consent_test"
        try {
            TwidgetStore.saveSettings(context, saved.copy(shareHistory = false))
            TopFollowersBridgeSyncWorker.enqueueScanRequest(context, username)
            assertTrue(WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("twidget-top-followers-bridge-$username").get().isEmpty())
        } finally {
            TwidgetStore.saveSettings(context, saved)
        }
    }

    /** Explicit opt-in only: uses the emulator's already opted-in public account. */
    @Test fun liveDashboardButtonRequestsBridgeAndDownloadsRanking() {
        val username = InstrumentationRegistry.getArguments().getString("liveBridgeUsername").orEmpty()
        assumeTrue("Pass liveBridgeUsername to run this network smoke test", username.isNotBlank())
        val settings = TwidgetStore.settings(context)
        assertEquals(settings.username, username)
        assertTrue("Enable shared history before the live test", settings.shareHistory)
        val debugWasEnabled = TwidgetStore.debugMenuUnlocked(context)
        TwidgetStore.setDebugMenuUnlocked(context, true)
        val startedAt = System.currentTimeMillis()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val previous = TopFollowersStore.read(context, username)
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                // Exercise the actual dashboard card listener, even if the account is cached.
                TopFollowersStore.write(context, username, TopFollowersState())
                val card = TopFollowersCardBinder(activity, {}).create(username)
                val button = descendants(card).filterIsInstance<Button>().single()
                assertEquals(context.getString(R.string.top_followers_find_with_bridge), button.text)
                assertTrue(button.performClick())
            }
            val manager = WorkManager.getInstance(context)
            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(8)
            var complete = false
            while (System.currentTimeMillis() < deadline) {
                val jobs = manager.getWorkInfosForUniqueWork("twidget-top-followers-bridge-${username.lowercase()}").get()
                assertFalse("Bridge worker failed: $jobs", jobs.any { it.state == WorkInfo.State.FAILED })
                if (jobs.any { it.state == WorkInfo.State.SUCCEEDED }) {
                    complete = true
                    break
                }
                Thread.sleep(1000)
            }
            assertTrue("Bridge scan did not finish within eight minutes", complete)
            val posts = BridgeLog.entries(context).filter {
                it.timestamp >= startedAt && it.method == "POST" &&
                    it.url.endsWith("/history/$username/top-followers/scan")
            }
            assertTrue("The app must send the scan request to the bridge", posts.isNotEmpty())
            assertTrue("Bridge must accept the scan", posts.any { it.code == 200 || it.code == 202 })
            val result = TopFollowersStore.read(context, username)
            assertTrue(result.complete)
            assertTrue(result.top.isNotEmpty())
            val archive = TopFollowersArchiveStore.readAll(context, username)
            assertTrue(archive.isNotEmpty())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nLive bridge success: @$username, POST codes=${posts.map { it.code }}, ${result.scanned} followers, ${archive.size} archived, completedAt=${result.completedAt}\n")
            })
        } finally {
            TwidgetStore.setDebugMenuUnlocked(context, debugWasEnabled)
            if (!TopFollowersStore.read(context, username).complete) TopFollowersStore.write(context, username, previous)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
