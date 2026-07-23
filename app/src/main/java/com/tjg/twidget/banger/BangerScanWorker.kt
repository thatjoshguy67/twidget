package com.tjg.twidget.banger

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.data.TwidgetStore
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
                publish(username, scanning = !result.complete && !result.capped, result.postsScanned)
                if (result.complete || result.capped) return Result.success()
            } while (System.currentTimeMillis() < deadline && !isStopped)
            Result.retry()
        } catch (_: Exception) {
            publish(username, scanning = true, postsScanned(applicationContext, username))
            Result.retry()
        }
    }

    private fun publish(username: String, scanning: Boolean, postsScanned: Int) {
        scanPrefs(applicationContext).edit()
            .putBoolean("scanning_${username.lowercase(Locale.US)}", scanning)
            .putInt("count_${username.lowercase(Locale.US)}", postsScanned)
            .apply()
        applicationContext.sendBroadcast(
            Intent(ACTION_UPDATED)
                .setPackage(applicationContext.packageName)
                .putExtra(EXTRA_USERNAME, username),
        )
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val RUN_BUDGET_MS = 3 * 60 * 1000L
        const val ACTION_UPDATED = "com.tjg.twidget.BANGER_SCAN_UPDATED"
        const val EXTRA_USERNAME = "username"

        fun isScanning(context: Context, username: String): Boolean =
            scanPrefs(context).getBoolean("scanning_${username.lowercase(Locale.US)}", false)

        fun postsScanned(context: Context, username: String): Int =
            scanPrefs(context).getInt("count_${username.lowercase(Locale.US)}", 0)

        fun clear(context: Context, username: String) {
            val key = username.lowercase(Locale.US)
            scanPrefs(context).edit().remove("scanning_$key").remove("count_$key").apply()
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork("twidget-banger-$key")
        }

        fun enqueue(context: Context, username: String) {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return
            scanPrefs(context).edit()
                .putBoolean("scanning_${clean.lowercase(Locale.US)}", true)
                .apply()
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

        private fun scanPrefs(context: Context) =
            context.getSharedPreferences("twidget_banger_scan", Context.MODE_PRIVATE)
    }
}
