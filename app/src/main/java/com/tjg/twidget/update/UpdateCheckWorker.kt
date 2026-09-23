package com.tjg.twidget.update

import com.tjg.twidget.BuildConfig

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.notices.ReleaseNoticesStore
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!BuildConfig.IN_APP_UPDATES) {
            cancelLegacyUpdates(context)
            return Result.success()
        }
        val installedVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return Result.failure()
        val check = runCatching {
            AppUpdateManager.checkReleases(
                TwidgetStore.updateCheckVersion(context, installedVersion),
                AboutActivity.savedUpdateChannel(context),
            )
        }.getOrElse { return Result.retry() }

        TwidgetStore.setUpdateAvailable(
            context,
            check.update != null,
            check.update?.version?.toString(),
        )
        if (check.notices.isNotEmpty()) ReleaseNoticesStore.save(context, check.notices)
        check.update?.let { UpdateNotificationHelper.showIfNeeded(context, it) }
            ?: UpdateNotificationHelper.cancel(context)
        return Result.success()
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "twidget_update_checks"
        private const val REMINDER_WORK_NAME = "twidget_update_reminder"
        private const val IMMEDIATE_WORK_NAME = "twidget_update_check_now"
        private const val CHECK_INTERVAL_HOURS = 6L
        private const val REMIND_LATER_HOURS = 24L

        fun schedule(context: Context) {
            if (!BuildConfig.IN_APP_UPDATES) {
                cancelLegacyUpdates(context)
                return
            }
            val request = PeriodicWorkRequest.Builder(
                UpdateCheckWorker::class.java,
                CHECK_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).setConstraints(networkConstraints()).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Runs a real GitHub release check immediately after a debug version override changes. */
        fun checkNow(context: Context) {
            if (!BuildConfig.IN_APP_UPDATES) return
            val request = OneTimeWorkRequest.Builder(UpdateCheckWorker::class.java)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
        fun scheduleReminder(context: Context) {
            if (!BuildConfig.IN_APP_UPDATES) {
                cancelLegacyUpdates(context)
                return
            }
            val request = OneTimeWorkRequest.Builder(UpdateCheckWorker::class.java)
                .setInitialDelay(REMIND_LATER_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                REMINDER_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        // The package ID is shared with GitHub builds. An existing installation
        // can retain its workers, notifications and cached APKs after migration.
        private fun cancelLegacyUpdates(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(PERIODIC_WORK_NAME)
            manager.cancelUniqueWork(REMINDER_WORK_NAME)
            UpdateNotificationHelper.cancel(context)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .deleteNotificationChannel("app_updates")
            TwidgetStore.setUpdateAvailable(context, false)
            TwidgetStore.setFakeUpdateAvailable(context, false)
            java.io.File(context.cacheDir, "updates").deleteRecursively()
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
