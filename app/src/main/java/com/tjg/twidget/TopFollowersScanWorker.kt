package com.tjg.twidget

import android.content.Context
import android.content.Intent
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

class TopFollowersScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val username = inputData.getString(KEY_USERNAME)?.trim()?.trimStart('@').orEmpty()
        if (username.isBlank()) return Result.success()
        val apiKey = SecureCredentialStore.read(applicationContext, SecureCredentialStore.TWITTERAPIS_API_KEY)
        if (apiKey.isBlank()) return fail(username, "Add a TwitterAPIs key in Advanced settings")

        var state = TopFollowersStore.read(applicationContext, username).copy(scanning = true, error = "")
        val deadline = System.currentTimeMillis() + RUN_BUDGET_MS
        return try {
            while (!isStopped && System.currentTimeMillis() < deadline) {
                if (state.pages >= MAX_PAGES_PER_SCAN) {
                    return fail(username, "Stopped at the $5 safety limit", state)
                }
                val page = TwitterApisClient.fetchFollowers(username, state.cursor, apiKey)
                if (page.users.isEmpty()) {
                    val completed = state.copy(
                        scanning = false,
                        complete = true,
                        error = "",
                        completedAt = System.currentTimeMillis(),
                    )
                    publish(username, completed)
                    if (!TwidgetAppVisibility.isVisible()) {
                        TopFollowersNotificationHelper.showComplete(applicationContext, username, completed)
                    }
                    return Result.success()
                }
                if (page.nextCursor.isBlank() || page.nextCursor == state.cursor) {
                    return fail(username, "TwitterAPIs pagination stopped unexpectedly", state)
                }
                val top = rankedTopFollowers(state.top + page.users, TOP_LIMIT)
                state = state.copy(
                    top = top,
                    cursor = page.nextCursor,
                    pages = state.pages + 1,
                    scanned = state.scanned + page.users.size,
                    scanning = true,
                )
                TopFollowersStore.write(applicationContext, username, state)
                // The cursor makes page requests sequential already. Do not add
                // an artificial delay: TwitterAPIs advertises no platform rate
                // cap, and WorkManager's 429 retry path remains the safety net.
                if (state.pages == 1 || state.pages % UI_UPDATE_PAGE_INTERVAL == 0) notifyUpdated(username)
            }
            Result.retry()
        } catch (error: HttpTransport.HttpException) {
            when (error.code) {
                401 -> fail(username, "TwitterAPIs rejected the API key", state)
                402 -> fail(username, "TwitterAPIs credit balance is empty", state)
                404 -> fail(username, "Follower list unavailable or account is private", state)
                400 -> fail(username, "TwitterAPIs rejected this username", state)
                else -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun fail(username: String, message: String, prior: TopFollowersState? = null): Result {
        publish(username, (prior ?: TopFollowersStore.read(applicationContext, username)).copy(
            scanning = false,
            error = message,
        ))
        return Result.failure()
    }

    private fun publish(username: String, state: TopFollowersState) {
        TopFollowersStore.write(applicationContext, username, state)
        notifyUpdated(username)
    }

    private fun notifyUpdated(username: String) {
        applicationContext.sendBroadcast(
            Intent(ACTION_UPDATED).setPackage(applicationContext.packageName).putExtra(EXTRA_USERNAME, username),
        )
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val RUN_BUDGET_MS = 3 * 60 * 1000L
        private const val UI_UPDATE_PAGE_INTERVAL = 25
        private const val MAX_PAGES_PER_SCAN = 6250 // $5 at the documented $0.0008/read.
        private const val TOP_LIMIT = 5
        const val ACTION_UPDATED = "com.tjg.twidget.TOP_FOLLOWERS_UPDATED"
        const val EXTRA_USERNAME = "username"

        fun enqueue(context: Context, username: String, restart: Boolean) {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return
            if (restart) TopFollowersStore.reset(context, clean)
            val request = OneTimeWorkRequestBuilder<TopFollowersScanWorker>()
                .setInputData(Data.Builder().putString(KEY_USERNAME, clean).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "twidget-top-followers-${clean.lowercase(Locale.US)}",
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
