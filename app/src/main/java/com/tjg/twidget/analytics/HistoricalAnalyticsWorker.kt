package com.tjg.twidget.analytics

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
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Fetches bounded archive batches independently of foreground pull-to-refresh. */
class HistoricalAnalyticsWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val account = inputData.getString("account") ?: return Result.success()
        val initial = TwidgetStore.settings(applicationContext)
        val endpoint = TwidgetStore.bridgeEndpoint(initial)
        val generation = inputData.getLong("generation", -1L)
        val userId = inputData.getString("userId").orEmpty()
        fun active(): Boolean {
            val settings = TwidgetStore.settings(applicationContext)
            val current = TwidgetStore.bridgeEndpoint(settings)
            return !isStopped && allowed(settings) && settings.dataSource == initial.dataSource &&
                current == endpoint && inputData.getString("source") == sourceKey(settings) &&
                TwidgetStore.accounts(applicationContext).any { it.equals(account, true) } &&
                TwidgetStore.accountGeneration(applicationContext, account) == generation &&
                TwidgetStore.currentStats(applicationContext, account).let { it.userId == userId && it.isPrivate != true }
        }
        if (!active()) return Result.success()
        if (runAttemptCount >= 5) return Result.failure()
        val encoded = URLEncoder.encode(account, StandardCharsets.UTF_8.name())
        fun save(root: JSONObject): Boolean {
            if (!active()) return false
            TwidgetStore.withAccountGeneration(applicationContext, account, generation) {
                if (active()) AnalyticsClient.cacheArchive(applicationContext, account, root, userId)
            }
            applicationContext.sendBroadcast(Intent(ACTION_UPDATED).setPackage(applicationContext.packageName)
                .putExtra("username", account))
            return true
        }
        return try {
            var root = JSONObject(AnalyticsClient.read("${endpoint.url}/analytics/$encoded?all=true", endpoint.token, applicationContext))
            // A new archive can be empty until its first backfill binds the account ID.
            if (root.optString("userId").isNotBlank()) {
                HistoricalPostArchive.requireIdentity(root, account, userId)
                if (!save(root)) return Result.success()
            }
            repeat(3) {
                if (!active()) return Result.success()
                if (!HistoricalPostArchive.coverage(root).needsBackfill(System.currentTimeMillis())) return Result.success()
                val headers = buildMap {
                    put("Content-Type", "application/json")
                    if (endpoint.token.isNotBlank()) {
                        put("Authorization", "Bearer ${endpoint.token}")
                        put("X-Rettiwt-Api-Key", endpoint.token)
                    }
                }
                val response = HttpTransport.post("${endpoint.url}/analytics/$encoded/backfill", "", headers,
                    connectTimeoutMs = 12_000, readTimeoutMs = 90_000, userAgent = "Twidget (Android)")
                if (response.code in setOf(401, 403, 404)) return Result.failure()
                root = JSONObject(HttpTransport.requireSuccess(response, "Historical analytics"))
                if (!save(root)) return Result.success()
                if (HistoricalPostArchive.coverage(root).status == "error") return Result.retry()
            }
            if (HistoricalPostArchive.coverage(root).needsBackfill(System.currentTimeMillis())) Result.retry() else Result.success()
        } catch (_: IllegalArgumentException) {
            Result.failure()
        } catch (_: Exception) {
            if (active()) Result.retry() else Result.success()
        }
    }

    companion object {
        const val ACTION_UPDATED = "com.tjg.twidget.ANALYTICS_ARCHIVE_UPDATED"
        internal fun allowed(settings: TwidgetSettings): Boolean = settings.shareHistory ||
            settings.dataSource in setOf(TwidgetStore.DATA_SOURCE_DEFAULT, TwidgetStore.DATA_SOURCE_SELF_HOSTED)
        private fun sourceKey(settings: TwidgetSettings): String =
            "${settings.dataSource}:${TwidgetStore.bridgeEndpoint(settings).url}"

        fun enqueue(context: Context, username: String) {
            val account = username.trim().trimStart('@').lowercase(Locale.US)
            val settings = TwidgetStore.settings(context)
            val profile = TwidgetStore.currentStats(context, account)
            if (!allowed(settings) || account.isBlank() || profile.userId.isBlank() || profile.isPrivate == true) return
            val analytics = AnalyticsClient.cached(context, account)
            if (analytics != null && !analytics.archiveCoverage.needsBackfill(System.currentTimeMillis()) &&
                System.currentTimeMillis() - analytics.historicalCheckedAt < 3_600_000) return
            val request = OneTimeWorkRequestBuilder<HistoricalAnalyticsWorker>()
                .setInputData(Data.Builder().putString("account", account).putString("userId", profile.userId)
                    .putString("source", sourceKey(settings))
                    .putLong("generation", TwidgetStore.accountGeneration(context, account)).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "twidget-history-$account-${sourceKey(settings).hashCode()}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
