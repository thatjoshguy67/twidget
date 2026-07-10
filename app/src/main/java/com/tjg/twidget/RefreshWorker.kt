package com.tjg.twidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background sync behind the "Refresh interval" setting. Widget providers only
 * re-render cached stats on their own update cycle; this worker is what
 * actually fetches fresh numbers.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!TwidgetStore.isOnboarded(context)) return Result.success()

        var anySuccess = false
        accountsToSync(context).forEach { account ->
            runCatching {
                TwidgetStore.saveStats(context, RettiwtClient.refresh(context, account))
                anySuccess = true
            }
        }
        if (anySuccess) {
            TwidgetWidget.updateAll(context)
            LockScreenFollowerServiceBoxReceiver.refresh(context)
        }
        return if (anySuccess) Result.success() else Result.retry()
    }

    // Every tracked account plus any legacy widget-pinned account that is no
    // longer in the account list.
    private fun accountsToSync(context: Context): List<String> {
        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = listOf(
            TwidgetWidget::class.java,
            LockScreenFollowerSmallWidget::class.java,
            LockScreenFollowerWideWidget::class.java,
        ).flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }
        val accounts = TwidgetStore.accounts(context) +
            widgetIds.map { TwidgetStore.widgetSettings(context, it).accountUsername } +
            TwidgetStore.settings(context).username
        return accounts
            .map { it.trim().trimStart('@') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    companion object {
        private const val WORK_NAME = "twidget_periodic_refresh"

        /** Idempotent; call whenever the interval setting may have changed. */
        fun schedule(context: Context) {
            val minutes = TwidgetStore.settings(context).refreshIntervalMinutes
                .coerceAtLeast(15) // WorkManager's minimum periodic interval
                .toLong()
            val request = PeriodicWorkRequest.Builder(RefreshWorker::class.java, minutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
