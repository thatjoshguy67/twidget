package com.tjg.twidget.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.AboutActivity
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a disposable emulator: deliberately seeds and cleans legacy updater state. */
@RunWith(AndroidJUnit4::class)
class PlayDistributionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun playManifestHasNoSideloadComponents() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requestedPermissions = info.requestedPermissions.orEmpty()
        assertFalse(requestedPermissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertFalse(requestedPermissions.contains("android.permission.POST_PROMOTED_NOTIFICATIONS"))
        assertNull(context.packageManager.resolveContentProvider("${context.packageName}.update_files", 0))
        val receivers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_RECEIVERS).receivers.orEmpty()
        assertFalse(receivers.any { it.name.endsWith("UpdateReminderReceiver") })
    }

    @Test fun legacyUpdaterStateIsCancelledAndHidden() {
        val manager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequest.Builder(UpdateCheckWorker::class.java, 6, TimeUnit.HOURS)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        val reminder = OneTimeWorkRequest.Builder(UpdateCheckWorker::class.java)
            .setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueueUniquePeriodicWork("twidget_update_checks", ExistingPeriodicWorkPolicy.REPLACE, periodic).result.get()
        manager.enqueueUniqueWork("twidget_update_reminder", ExistingWorkPolicy.REPLACE, reminder).result.get()
        TwidgetStore.setUpdateAvailable(context, true, "99.0.0")
        TwidgetStore.setFakeUpdateAvailable(context, true)
        val apk = File(context.cacheDir, "updates/old.apk").apply { parentFile!!.mkdirs(); writeText("old update") }
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel("app_updates", "Updates", NotificationManager.IMPORTANCE_DEFAULT))

        // Old badges must already be hidden before asynchronous cleanup runs.
        assertFalse(TwidgetStore.updateAvailable(context))
        assertNull(TwidgetStore.updateSuggestionVersion(context))
        UpdateCheckWorker.schedule(context)
        assertFalse(apk.exists())
        assertNull(notifications.getNotificationChannel("app_updates"))
        assertTrue(manager.getWorkInfoById(periodic.id).get()!!.state.isFinished)
        assertTrue(manager.getWorkInfoById(reminder.id).get()!!.state.isFinished)
    }

    @Test fun aboutIgnoresLegacyInstallIntentAndHasNoUpdateControls() {
        val activity = instrumentation.startActivitySync(
            AboutActivity.installUpdateIntent(context, "99.0.0").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as AboutActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(activity.findViewById<SwipeRefreshLayout>(R.id.about_refresh).isEnabled)
                assertNotEquals(View.VISIBLE, activity.findViewById<View>(R.id.about_update_action).visibility)
                val menu = activity.findViewById<Toolbar>(R.id.about_toolbar).menu
                assertFalse((0 until menu.size()).any { menu.getItem(it).title == activity.getString(R.string.settings_update_channel) })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
