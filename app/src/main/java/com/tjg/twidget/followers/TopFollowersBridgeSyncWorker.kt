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
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.TwidgetAppVisibility
import com.tjg.twidget.widget.TwidgetBriefWidget
import com.tjg.twidget.widget.TwidgetWidget
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Downloads completed bridge rankings without exposing server scan progress in the client UI. */
class TopFollowersBridgeSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val username = inputData.getString(KEY_USERNAME).orEmpty().trim().trimStart('@')
        if (username.isBlank() || !TwidgetStore.settings(applicationContext).shareHistory) {
            return Result.success()
        }
        return try {
            if (inputData.getBoolean(KEY_REQUEST_SCAN, false)) {
                val remote = TopFollowersBridgeCache.startScan(applicationContext, username)
                when {
                    remote.scanning -> return Result.retry()
                    remote.error.isNotBlank() -> return Result.failure()
                }
            }
            if (TopFollowersBridgeSync.refresh(applicationContext, username, notifyChanges = true) == null) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_REQUEST_SCAN = "request_scan"

        fun enqueueScanRequest(context: Context, username: String) {
            val clean = username.trim().trimStart('@')
            if (clean.isBlank()) return
            val request = OneTimeWorkRequestBuilder<TopFollowersBridgeSyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_USERNAME, clean)
                        .putBoolean(KEY_REQUEST_SCAN, true)
                        .build(),
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "twidget-top-followers-bridge-${clean.lowercase(Locale.US)}",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

internal object TopFollowersBridgeSync {
    fun refresh(
        context: Context,
        username: String,
        notifyChanges: Boolean,
        forceArchiveRefresh: Boolean = false,
    ): TopFollowersState? {
        val previousState = TopFollowersStore.read(context, username)
        val previousFollowers = TopFollowersArchiveStore.readAll(context, username)
            .ifEmpty { previousState.top }
        val latest = TopFollowersBridgeCache.fetch(
            context,
            username,
            completedAfter = if (forceArchiveRefresh) 0L else previousState.completedAt,
        ) ?: return null
        if (!forceArchiveRefresh && latest.completedAt <= previousState.completedAt && previousState.complete) {
            return previousState
        }

        val completed = latest.copy(
            scanning = false,
            complete = true,
            error = "",
            activeRunId = "",
        )
        TopFollowersStore.write(context, username, completed)
        context.applicationContext.sendBroadcast(
            Intent(TopFollowersScanWorker.ACTION_UPDATED)
                .setPackage(context.packageName)
                .putExtra(TopFollowersScanWorker.EXTRA_USERNAME, username),
        )

        if (notifyChanges && previousState.complete && !TwidgetAppVisibility.isVisible()) {
            newHighRankingFollower(previousFollowers, completed.top)?.let { ranked ->
                TopFollowersNotificationHelper.showNewHighRankingFollower(
                    context,
                    username,
                    ranked.follower,
                    ranked.rank,
                )
            }
        }
        if (BriefSettingsStore.enabled(context) && username.equals(
                TwidgetStore.settings(context).username,
                ignoreCase = true,
            )
        ) {
            runCatching { BriefEngine.rebuild(context, username, force = true) }
            TwidgetBriefWidget.updateAll(context)
        }
        TwidgetWidget.updateAll(context)
        return completed
    }
}

internal data class NewHighRankingFollower(
    val follower: TopFollower,
    val rank: Int,
)

internal fun newHighRankingFollower(
    previous: List<TopFollower>,
    current: List<TopFollower>,
    maxRank: Int = 50,
): NewHighRankingFollower? {
    if (previous.isEmpty() || current.isEmpty() || maxRank <= 0) return null
    val previousIdentities = previous.map(::topFollowerIdentity).toSet()
    return TopFollowersBrowserPolicy.apply(current)
        .take(maxRank)
        .firstOrNull { topFollowerIdentity(it.follower) !in previousIdentities }
        ?.let { NewHighRankingFollower(it.follower, it.rank) }
}

private fun topFollowerIdentity(follower: TopFollower): String =
    follower.id.ifBlank { follower.username.trim().trimStart('@') }.lowercase(Locale.US)
