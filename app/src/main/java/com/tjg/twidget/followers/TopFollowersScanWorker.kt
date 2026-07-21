package com.tjg.twidget.followers

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
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.providers.TwitterApisClient
import com.tjg.twidget.providers.TwitterApisAccessSource
import com.tjg.twidget.ui.TwidgetAppVisibility
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TopFollowersScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val username = inputData.getString(KEY_USERNAME)?.trim()?.trimStart('@').orEmpty()
        if (username.isBlank()) return Result.success()
        val access = TwitterApisClient.topFollowersAccess(applicationContext)
            ?: return fail(username, "TwitterAPIs access is not configured")
        if (inputData.getBoolean(KEY_PERSONAL_ACCESS_REQUIRED, false) &&
            access.source != TwitterApisAccessSource.PERSONAL
        ) {
            return fail(username, "Your personal TwitterAPIs key was removed; start the scan again")
        }

        TopFollowersActiveScans.started(username)
        var state = TopFollowersStore.read(applicationContext, username).copy(scanning = true, error = "")
        publish(username, state)
        val latestState = AtomicReference(state)
        val scanActive = AtomicBoolean(true)
        val visibilityRegistration = TwidgetAppVisibility.addVisibilityListener { visible ->
            if (visible || !scanActive.get()) return@addVisibilityListener
            val stateWhenHidden = latestState.get()
            AppExecutors.execute(
                onRejected = { promoteToForegroundIfAppIsHidden(username, stateWhenHidden) },
            ) {
                if (scanActive.get()) promoteToForegroundIfAppIsHidden(username, latestState.get())
            }
        }
        return try {
            var transientFailures = 0
            while (!isStopped) {
                promoteToForegroundIfAppIsHidden(username, state)
                if (state.pages >= MAX_PAGES_PER_SCAN) {
                    return fail(username, "Stopped at the $5 safety limit", state)
                }
                val page = try {
                    TwitterApisClient.fetchFollowers(username, state.cursor, access.apiKey)
                } catch (error: HttpTransport.HttpException) {
                    when (error.code) {
                        401 -> return fail(username, "TwitterAPIs rejected the API key", state)
                        402 -> return fail(username, "TwitterAPIs credit balance is empty", state)
                        404 -> return fail(username, "Follower list unavailable or account is private", state)
                        400 -> return fail(username, "TwitterAPIs rejected this username", state)
                    }
                    transientFailures += 1
                    if (!waitForTransientRetry(transientFailures)) return retryLater(username, state)
                    continue
                } catch (_: Exception) {
                    transientFailures += 1
                    if (!waitForTransientRetry(transientFailures)) return retryLater(username, state)
                    continue
                }
                transientFailures = 0
                if (page.users.isEmpty()) {
                    return complete(username, state)
                }
                if (page.nextCursor == state.cursor) {
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
                latestState.set(state)
                if (page.nextCursor.isBlank()) return complete(username, state)
                TopFollowersStore.write(applicationContext, username, state)
                promoteToForegroundIfAppIsHidden(username, state)
                // The cursor makes page requests sequential already. Do not add
                // an artificial delay: TwitterAPIs advertises no platform rate
                // cap. Transient failures are retried without dropping the live
                // progress notification.
                notifyUpdated(username)
            }
            retryLater(username, state)
        } catch (_: Exception) {
            retryLater(username, state)
        } finally {
            scanActive.set(false)
            visibilityRegistration.close()
            TopFollowersActiveScans.finished(username)
        }
    }

    private fun promoteToForegroundIfAppIsHidden(username: String, state: TopFollowersState) {
        if (TwidgetAppVisibility.isVisible()) return
        setForegroundAsync(
            TopFollowersNotificationHelper.progressForegroundInfo(applicationContext, username, state),
        ).get()
    }

    private fun waitForTransientRetry(attempt: Int): Boolean {
        val delayMs = TopFollowersRetryPolicy.delayMs(attempt) ?: return false
        val deadline = System.currentTimeMillis() + delayMs
        while (!isStopped) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return true
            Thread.sleep(remaining.coerceAtMost(RETRY_SLEEP_SLICE_MS))
        }
        return false
    }

    private fun retryLater(username: String, prior: TopFollowersState): Result {
        // WorkManager removes a worker's foreground notification during its
        // backoff period. Reflect that pause in persisted UI state so the card
        // never claims an inactive scan is still progressing without a live
        // notification. The next attempt publishes scanning=true immediately.
        publish(username, prior.copy(scanning = false, error = ""))
        TopFollowersNotificationHelper.cancelProgress(applicationContext, username)
        return Result.retry()
    }

    private fun complete(username: String, prior: TopFollowersState): Result {
        val completed = prior.copy(
            scanning = false,
            complete = true,
            error = "",
            completedAt = System.currentTimeMillis(),
        )
        publish(username, completed)
        TopFollowersNotificationHelper.cancelProgress(applicationContext, username)
        if (!TwidgetAppVisibility.isVisible()) {
            TopFollowersNotificationHelper.showComplete(applicationContext, username, completed)
        }
        return Result.success()
    }

    private fun fail(username: String, message: String, prior: TopFollowersState? = null): Result {
        publish(username, (prior ?: TopFollowersStore.read(applicationContext, username)).copy(
            scanning = false,
            error = message,
        ))
        TopFollowersNotificationHelper.cancelProgress(applicationContext, username)
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
        private const val KEY_PERSONAL_ACCESS_REQUIRED = "personal_access_required"
        private const val MAX_PAGES_PER_SCAN = 6250 // $5 at the documented $0.0008/read.
        private const val TOP_LIMIT = 5
        private const val RETRY_SLEEP_SLICE_MS = 500L
        const val ACTION_UPDATED = "com.tjg.twidget.TOP_FOLLOWERS_UPDATED"
        const val EXTRA_USERNAME = "username"

        fun cancel(context: Context, username: String) {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(clean))
            val stopped = TopFollowersStore.read(context, clean).copy(scanning = false, error = "")
            TopFollowersStore.write(context, clean, stopped)
            TopFollowersNotificationHelper.cancelProgress(context, clean)
            context.applicationContext.sendBroadcast(
                Intent(ACTION_UPDATED).setPackage(context.packageName).putExtra(EXTRA_USERNAME, clean),
            )
        }

        fun enqueue(context: Context, username: String, restart: Boolean): TopFollowersScanStart {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return TopFollowersScanStart.ALREADY_SCANNED_TODAY
            val access = TwitterApisClient.topFollowersAccess(context)
                ?: return TopFollowersScanStart.NO_API_KEY
            val startResult = if (restart) {
                TopFollowersStore.tryStartScan(
                    context,
                    clean,
                    dailyLimitEnabled = access.source == TwitterApisAccessSource.APP_DEFAULT,
                )
            } else {
                TopFollowersScanStart.STARTED
            }
            if (startResult != TopFollowersScanStart.STARTED) return startResult
            val request = OneTimeWorkRequestBuilder<TopFollowersScanWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_USERNAME, clean)
                        .putBoolean(
                            KEY_PERSONAL_ACCESS_REQUIRED,
                            access.source == TwitterApisAccessSource.PERSONAL,
                        )
                        .build(),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(clean),
                if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            return TopFollowersScanStart.STARTED
        }

        private fun workName(username: String) =
            "twidget-top-followers-${username.lowercase(Locale.US)}"
    }
}

internal object TopFollowersRetryPolicy {
    private val delaysMs = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    fun delayMs(attempt: Int): Long? = delaysMs.getOrNull(attempt - 1)
}

/** Process-local truth used to avoid rendering stale persisted scanning state after process death. */
internal object TopFollowersActiveScans {
    private val usernames = mutableSetOf<String>()

    @Synchronized fun started(username: String) {
        usernames += key(username)
    }

    @Synchronized fun finished(username: String) {
        usernames -= key(username)
    }

    @Synchronized fun isActive(username: String): Boolean = key(username) in usernames

    private fun key(username: String) = username.trim().trimStart('@').lowercase(Locale.US)
}
