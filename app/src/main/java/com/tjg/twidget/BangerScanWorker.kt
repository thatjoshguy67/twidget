package com.tjg.twidget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Drives the resumable Hall of Fame scan to exhaustion outside UI refreshes. */
class BangerScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val username = inputData.getString(KEY_USERNAME)?.trim()?.trimStart('@').orEmpty()
        if (username.isBlank() || TwidgetStore.accounts(applicationContext).none { it.equals(username, true) }) {
            return Result.success()
        }
        val deadline = System.currentTimeMillis() + RUN_BUDGET_MS
        return try {
            do {
                val settings = TwidgetStore.settings(applicationContext)
                val result = BangerClient.refresh(
                    applicationContext,
                    username,
                    settings,
                    TwidgetStore.bridgeEndpoint(settings),
                )
                AnalyticsClient.cacheBanger(applicationContext, username, result)
                if (result.complete || result.capped) return Result.success()
            } while (System.currentTimeMillis() < deadline && !isStopped)
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val RUN_BUDGET_MS = 3 * 60 * 1000L

        fun enqueue(context: Context, username: String) {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return
            val request = OneTimeWorkRequestBuilder<BangerScanWorker>()
                .setInputData(Data.Builder().putString(KEY_USERNAME, clean).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "twidget-banger-${clean.lowercase(Locale.US)}",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
