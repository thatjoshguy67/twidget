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
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.widget.TwidgetBriefWidget
import com.tjg.twidget.providers.TwitterApisClient
import com.tjg.twidget.providers.TwitterApisAccessSource
import com.tjg.twidget.providers.XApiClient
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetAppVisibility
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class TopFollowersScanWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val username = inputData.getString(KEY_USERNAME)?.trim()?.trimStart('@').orEmpty()
        if (username.isBlank()) return Result.success()
        val runId = inputData.getString(KEY_RUN_ID).orEmpty()
        if (runId.isBlank()) {
            val legacyState = TopFollowersStore.read(applicationContext, username)
            if (legacyState.activeRunId.isBlank() && legacyState.scanning) {
                TopFollowersStore.stopScan(applicationContext, username)
            }
            return Result.success()
        }
        val source = inputData.getString(KEY_SOURCE).orEmpty()
        val twitterAccess = if (source == SOURCE_TWITTERAPIS) {
            TwitterApisClient.topFollowersAccess(applicationContext)
                ?: return fail(username, runId, "TwitterAPIs access is not configured")
        } else null
        if (source == SOURCE_TWITTERAPIS && inputData.getBoolean(KEY_PERSONAL_ACCESS_REQUIRED, false) &&
            twitterAccess?.source != TwitterApisAccessSource.PERSONAL
        ) return fail(username, runId, "Your personal TwitterAPIs key was removed; start the scan again")
        val xAccess = if (source == SOURCE_X_API) {
            runCatching { XApiClient.followersAccess(applicationContext, username) }.getOrNull()
                ?: return fail(username, runId, "Official X API access is not configured or was rejected")
        } else null
        if (twitterAccess == null && xAccess == null) {
            return fail(username, runId, "No follower-list provider is configured")
        }
        if (!TopFollowersStore.isRunCurrent(applicationContext, username, runId)) return Result.success()

        TopFollowersActiveScans.started(username, runId)
        var state = TopFollowersStore.read(applicationContext, username).copy(scanning = true, error = "")
        if (!publish(username, runId, state)) return Result.success()
        updateForeground(username, state)
        return try {
            var transientFailures = 0
            while (!isStopped) {
                if (!TopFollowersStore.isRunCurrent(applicationContext, username, runId)) return Result.success()
                if (state.pages >= MAX_PAGES_PER_SCAN) {
                    return fail(username, runId, "Stopped at the $5 safety limit", state)
                }
                val page = try {
                    if (xAccess != null) {
                        XApiClient.fetchFollowers(xAccess, state.cursor)
                    } else {
                        TwitterApisClient.fetchFollowers(username, state.cursor, requireNotNull(twitterAccess).apiKey)
                    }
                } catch (error: HttpTransport.HttpException) {
                    when (error.code) {
                        401 -> return fail(username, runId, "TwitterAPIs rejected the API key", state)
                        402 -> return fail(username, runId, "TwitterAPIs credit balance is empty", state)
                        404 -> return fail(username, runId, "Follower list unavailable or account is private", state)
                        400 -> return fail(username, runId, "TwitterAPIs rejected this username", state)
                    }
                    transientFailures += 1
                    if (!waitForTransientRetry(transientFailures)) return retryLater(username, runId, state)
                    continue
                } catch (_: Exception) {
                    transientFailures += 1
                    if (!waitForTransientRetry(transientFailures)) return retryLater(username, runId, state)
                    continue
                }
                if (isStopped || !TopFollowersStore.isRunCurrent(applicationContext, username, runId)) {
                    return Result.success()
                }
                transientFailures = 0
                if (page.users.isEmpty()) {
                    return complete(username, runId, state)
                }
                if (page.nextCursor == state.cursor) {
                    return fail(username, runId, "TwitterAPIs pagination stopped unexpectedly", state)
                }
                val top = rankedTopFollowers(state.top + page.users, TOP_LIMIT)
                TopFollowersArchiveStore.append(applicationContext, username, page.users, state.pages + 1)
                state = state.copy(
                    top = top,
                    cursor = page.nextCursor,
                    pages = state.pages + 1,
                    scanned = state.scanned + page.users.size,
                    scanning = true,
                )
                if (page.nextCursor.isBlank()) return complete(username, runId, state)
                if (!publish(username, runId, state)) return Result.success()
                updateForeground(username, state)
                // The cursor makes page requests sequential already. Do not add
                // an artificial delay: TwitterAPIs advertises no platform rate
                // cap. Transient failures are retried without dropping the live
                // progress notification.
            }
            Result.success()
        } catch (_: Exception) {
            retryLater(username, runId, state)
        } finally {
            TopFollowersActiveScans.finished(username, runId)
        }
    }

    private fun updateForeground(username: String, state: TopFollowersState) {
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

    private fun retryLater(username: String, runId: String, prior: TopFollowersState): Result {
        // WorkManager removes a worker's foreground notification during its
        // backoff period. Reflect that pause in persisted UI state so the card
        // never claims an inactive scan is still progressing without a live
        // notification. The next attempt publishes scanning=true immediately.
        if (!publish(username, runId, prior.copy(scanning = false, error = ""))) return Result.success()
        TopFollowersNotificationHelper.cancelProgress(applicationContext, username)
        return Result.retry()
    }

    private fun complete(username: String, runId: String, prior: TopFollowersState): Result {
        val completed = prior.copy(
            scanning = false,
            complete = true,
            error = "",
            completedAt = System.currentTimeMillis(),
        )
        if (!publish(username, runId, completed, finished = true)) return Result.success()
        TopFollowersNotificationHelper.cancelProgress(applicationContext, username)
        if (!TwidgetAppVisibility.isVisible()) {
            TopFollowersNotificationHelper.showComplete(applicationContext, username, completed)
        }
        if (BriefSettingsStore.enabled(applicationContext) && username.equals(
                TwidgetStore.settings(applicationContext).username,
                ignoreCase = true,
            )
        ) {
            runCatching { BriefEngine.rebuild(applicationContext, username, force = true) }
            TwidgetBriefWidget.updateAll(applicationContext)
        }
        return Result.success()
    }

    private fun fail(username: String, runId: String, message: String, prior: TopFollowersState? = null): Result {
        if (!publish(username, runId, (prior ?: TopFollowersStore.read(applicationContext, username)).copy(
            scanning = false,
            error = message,
        ), finished = true)) return Result.success()
        TopFollowersNotificationHelper.cancelProgress(applicationContext, username)
        return Result.failure()
    }

    private fun publish(
        username: String,
        runId: String,
        state: TopFollowersState,
        finished: Boolean = false,
    ): Boolean {
        if (!TopFollowersStore.writeForRun(applicationContext, username, runId, state, finished)) return false
        notifyUpdated(username)
        return true
    }

    private fun notifyUpdated(username: String) {
        applicationContext.sendBroadcast(
            Intent(ACTION_UPDATED).setPackage(applicationContext.packageName).putExtra(EXTRA_USERNAME, username),
        )
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_RUN_ID = "run_id"
        private const val KEY_PERSONAL_ACCESS_REQUIRED = "personal_access_required"
        private const val KEY_SOURCE = "source"
        private const val SOURCE_TWITTERAPIS = "twitterapis"
        private const val SOURCE_X_API = "x_api"
        private const val MAX_PAGES_PER_SCAN = 6250 // $5 at the documented $0.0008/read.
        // Brief compares meaningful movement between daily scans. Keeping the
        // top 100 is small enough for preferences but deep enough to spot a
        // follower rising into the visible ranks.
        private const val TOP_LIMIT = 100
        private const val RETRY_SLEEP_SLICE_MS = 500L
        const val ACTION_UPDATED = "com.tjg.twidget.TOP_FOLLOWERS_UPDATED"
        const val EXTRA_USERNAME = "username"

        fun cancel(context: Context, username: String) {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return
            TopFollowersStore.stopScan(context, clean)
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(clean))
            TopFollowersNotificationHelper.cancelProgress(context, clean)
            context.applicationContext.sendBroadcast(
                Intent(ACTION_UPDATED).setPackage(context.packageName).putExtra(EXTRA_USERNAME, clean),
            )
        }

        fun enqueue(
            context: Context,
            username: String,
            restart: Boolean,
            dailyLimitEnabledOverride: Boolean? = null,
        ): TopFollowersScanStart = enqueueWithSource(
            context = context,
            username = username,
            restart = restart,
            dailyLimitEnabledOverride = dailyLimitEnabledOverride,
            sourceOverride = null,
        )

        /** Explicit user refreshes prefer a linked on-device API over the shared bridge. */
        internal fun enqueueLinkedApiRefresh(
            context: Context,
            username: String,
        ): TopFollowersScanStart {
            val source = linkedApiScanSource(context) ?: return TopFollowersScanStart.NO_API_KEY
            return enqueueWithSource(
                context = context,
                username = username,
                restart = true,
                dailyLimitEnabledOverride = false,
                sourceOverride = source,
            )
        }

        internal fun linkedApiScanSource(context: Context): TopFollowersScanSource? {
            val settings = TwidgetStore.settings(context)
            return selectLinkedApiScanSource(
                selectedXApi = XApiClient.hasCredentials(settings) &&
                    settings.dataSource == TwidgetStore.DATA_SOURCE_X_API,
                personalTwitterApis = TwitterApisClient.topFollowersAccess(context)?.source ==
                    TwitterApisAccessSource.PERSONAL,
                fallbackXApi = XApiClient.hasCredentials(settings),
            )
        }

        private fun enqueueWithSource(
            context: Context,
            username: String,
            restart: Boolean,
            dailyLimitEnabledOverride: Boolean?,
            sourceOverride: TopFollowersScanSource?,
        ): TopFollowersScanStart {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return TopFollowersScanStart.ALREADY_SCANNED_TODAY
            val settings = TwidgetStore.settings(context)
            val twitterAccess = TwitterApisClient.topFollowersAccess(context)
            val hasXAccess = XApiClient.hasCredentials(settings)
            val selectedSource = sourceOverride ?: selectTopFollowersScanSource(
                selectedXApi = hasXAccess && settings.dataSource == TwidgetStore.DATA_SOURCE_X_API,
                personalTwitterApis = twitterAccess?.source == TwitterApisAccessSource.PERSONAL,
                fallbackXApi = hasXAccess,
            )
            val source = selectedSource?.wireValue ?: return TopFollowersScanStart.NO_API_KEY
            val runId = UUID.randomUUID().toString()
            val startResult = if (restart) {
                TopFollowersStore.tryStartScan(
                    context,
                    clean,
                    runId,
                    dailyLimitEnabled = dailyLimitEnabledOverride
                        ?: (source == SOURCE_X_API),
                )
            } else {
                val resumed = TopFollowersStore.read(context, clean).copy(
                    scanning = true,
                    error = "",
                    activeRunId = runId,
                )
                TopFollowersStore.write(context, clean, resumed)
                TopFollowersScanStart.STARTED
            }
            if (startResult != TopFollowersScanStart.STARTED) return startResult
            val request = OneTimeWorkRequestBuilder<TopFollowersScanWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_USERNAME, clean)
                        .putString(KEY_RUN_ID, runId)
                        .putBoolean(
                            KEY_PERSONAL_ACCESS_REQUIRED,
                            source == SOURCE_TWITTERAPIS &&
                                twitterAccess?.source == TwitterApisAccessSource.PERSONAL,
                        )
                        .putString(KEY_SOURCE, source)
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

internal enum class TopFollowersScanSource(val wireValue: String) {
    TWITTERAPIS("twitterapis"),
    X_API("x_api"),
}

internal fun selectTopFollowersScanSource(
    selectedXApi: Boolean,
    personalTwitterApis: Boolean,
    fallbackXApi: Boolean,
): TopFollowersScanSource? = when {
    selectedXApi -> TopFollowersScanSource.X_API
    personalTwitterApis -> TopFollowersScanSource.TWITTERAPIS
    fallbackXApi -> TopFollowersScanSource.X_API
    else -> null
}

internal fun selectLinkedApiScanSource(
    selectedXApi: Boolean,
    personalTwitterApis: Boolean,
    fallbackXApi: Boolean,
): TopFollowersScanSource? = selectTopFollowersScanSource(
    selectedXApi = selectedXApi,
    personalTwitterApis = personalTwitterApis,
    fallbackXApi = fallbackXApi,
)

internal object TopFollowersRetryPolicy {
    private val delaysMs = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    fun delayMs(attempt: Int): Long? = delaysMs.getOrNull(attempt - 1)
}

/** Process-local truth used to avoid rendering stale persisted scanning state after process death. */
internal object TopFollowersActiveScans {
    private val runs = mutableMapOf<String, String>()

    @Synchronized fun started(username: String, runId: String) {
        runs[key(username)] = runId
    }

    @Synchronized fun finished(username: String, runId: String) {
        runs.remove(key(username), runId)
    }

    @Synchronized fun isActive(username: String): Boolean = key(username) in runs

    private fun key(username: String) = username.trim().trimStart('@').lowercase(Locale.US)
}
