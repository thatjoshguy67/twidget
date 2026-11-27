package com.tjg.twidget.main

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ActivityClient
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.RettiwtClient
import com.tjg.twidget.widget.TwidgetWidget
import com.tjg.twidget.ui.TwidgetHaptics
import java.util.Locale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

internal class MainSyncController(
    private val activity: MainActivity,
    private val fetchOverride: (suspend (String, DashboardDataSource, Boolean) -> Boolean)? = null,
) {
    var isSyncing = false
        private set
    var lifecycleGeneration = 0L
        private set
    private val refreshes = DashboardRefreshCoordinator(activity.lifecycleScope)
    private val explicitAccounts = mutableSetOf<String>()
    private var backgroundSweep: Job? = null
    private fun accountKey(account: String) = account.trim().trimStart('@').lowercase(Locale.US)

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var rebindScheduled = false
    private fun scheduleDashboardBind() {
        if (rebindScheduled) return
        rebindScheduled = true
        val token = lifecycleGeneration
        uiHandler.postDelayed({
            rebindScheduled = false
            if (token == lifecycleGeneration && !activity.isDestroyed && !activity.isFinishing && !activity.editModeController.editMode) {
                activity.dashboardBinder.bindContent(refreshRemote = false)
            }
        }, 80L)
    }

    fun invalidateLifecycle() {
        lifecycleGeneration++
        backgroundSweep?.cancel()
        uiHandler.removeCallbacksAndMessages(null)
        rebindScheduled = false
    }

    fun setupRefresh() {
        activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).apply {
            setOnRefreshListener { handlePullRefresh() }
        }
    }

    fun handlePullRefresh() {
        sync(userInitiated = true)
    }

    fun sync(showProgress: Boolean = true, userInitiated: Boolean = false) {
        val account = activity.selectedAccount.ifBlank { TwidgetStore.settings(activity).username }
        if (!userInitiated) {
            val tasks = DashboardDataSource.entries.filter { it == DashboardDataSource.PROFILE || needsRefresh(account, it) }
                .map { request(account, it) }
            refreshOtherProfilesAfter(account, tasks, refreshAllProfiles = true)
            return
        }
        val key = accountKey(account)
        if (!explicitAccounts.add(key)) return
        isSyncing = true
        val refreshView = activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh)
        if (userInitiated) TwidgetHaptics.refresh(refreshView)
        refreshView.isRefreshing = showProgress
        com.tjg.twidget.analytics.WeeklyTimelineRepository.invalidateCompleted(account)
        val tasks = DashboardDataSource.entries.map { request(account, it, force = true) }
        refreshOtherProfilesAfter(account, tasks, refreshAllProfiles = false)
        activity.lifecycleScope.launch {
            val outcomes = tasks.map { it.await() }
            explicitAccounts.remove(key)
            isSyncing = explicitAccounts.isNotEmpty()
            if (!activity.selectedAccount.equals(account, true)) return@launch
            refreshView.isRefreshing = false
            activity.render(refreshRemote = false)
            if (outcomes.any { it.isFailure }) android.widget.Toast.makeText(activity, R.string.sync_failed, android.widget.Toast.LENGTH_SHORT).show()
            if (userInitiated && activity.hasWindowFocus()) {
                if (outcomes.any { it.isFailure }) TwidgetHaptics.reject(refreshView)
                else TwidgetHaptics.confirm(refreshView)
            }
        }
    }

    private fun needsRefresh(account: String, source: DashboardDataSource): Boolean {
        if (!refreshes.mayAttempt(account, source)) return false
        return when (source) {
            DashboardDataSource.PROFILE -> {
                val stats = TwidgetStore.currentStats(activity, account)
                stats.syncedAt <= 0 || System.currentTimeMillis() - stats.syncedAt >= 3_600_000L ||
                    (stats.profileExpiresAt > 0 && System.currentTimeMillis() >= stats.profileExpiresAt) ||
                    ((!stats.followersKnown || !stats.followingKnown || !stats.postsKnown) && refreshes.status(account, source) == null)
            }
            DashboardDataSource.POSTS -> AnalyticsClient.isStale(activity, AnalyticsClient.cached(activity, account)) && AnalyticsClient.mayAttempt(activity, account)
            DashboardDataSource.ACTIVITY -> ActivityClient.isStale(activity, account)
            DashboardDataSource.TOP_FOLLOWERS -> TwidgetStore.settings(activity).shareHistory &&
                com.tjg.twidget.followers.TopFollowersStore.read(activity, account).completedAt.let {
                    it <= 0 || System.currentTimeMillis() - it >= 3_600_000L
                }
        }
    }

    private fun refreshOtherProfilesAfter(priority: String, first: List<kotlinx.coroutines.Deferred<Result<Boolean>>>, refreshAllProfiles: Boolean) {
        backgroundSweep?.cancel()
        backgroundSweep = activity.lifecycleScope.launch {
            first.forEach { it.await() }
            val remaining = dashboardRefreshOrder(TwidgetStore.accounts(activity), priority).filterNot { it.equals(priority, true) }.toMutableList()
            while (remaining.isNotEmpty()) {
                // If the user changes profile mid-sweep, put the newly visible one next.
                val next = remaining.firstOrNull { it.equals(activity.selectedAccount, true) } ?: remaining.first()
                remaining.remove(next)
                if (TwidgetStore.accounts(activity).none { it.equals(next, true) }) continue
                val tasks = DashboardDataSource.entries.filter {
                    (refreshAllProfiles && it == DashboardDataSource.PROFILE) || needsRefresh(next, it)
                }.map { request(next, it) }
                tasks.forEach { it.await() }
            }
        }
    }

    private fun request(account: String, source: DashboardDataSource, force: Boolean = false): kotlinx.coroutines.Deferred<Result<Boolean>> {
        val task = refreshes.request(account, source) {
            withContext(Dispatchers.IO) {
                val context = activity.applicationContext
                fetchOverride?.let { return@withContext it(account, source, force) }
                when (source) {
                    DashboardDataSource.PROFILE -> {
                        val stats = RettiwtClient.refresh(context, account, forceSharedHistory = force)
                        TwidgetStore.saveStats(context, stats)
                        com.tjg.twidget.analytics.HistoricalAnalyticsWorker.enqueue(context, account)
                        TwidgetWidget.updateAll(context)
                        stats.followersKnown || stats.followingKnown || stats.postsKnown
                    }
                    DashboardDataSource.POSTS -> { AnalyticsClient.refresh(context, account); true }
                    DashboardDataSource.ACTIVITY -> ActivityClient.refresh(context, account, reportFailure = true).activityComplete
                    DashboardDataSource.TOP_FOLLOWERS -> if (!TwidgetStore.settings(context).shareHistory) false else
                        com.tjg.twidget.followers.TopFollowersBridgeSync.refresh(context, account,
                            notifyChanges = false, forceArchiveRefresh = force)?.complete == true
                }.also {
                    if (com.tjg.twidget.brief.BriefSettingsStore.enabled(context)) {
                        com.tjg.twidget.widget.TwidgetBriefWidget.updateAll(context)
                    }
                }
            }
        }
        activity.lifecycleScope.launch {
            task.await()
            if (activity.selectedAccount.equals(account, true) && !activity.editModeController.editMode) scheduleDashboardBind()
        }
        return task
    }

    private fun maybeRefresh(account: String, source: DashboardDataSource) {
        if (!activity.editModeController.editMode && needsRefresh(account, source)) request(account, source)
    }
    fun maybeRefreshProfile(account: String) = maybeRefresh(account, DashboardDataSource.PROFILE)
    fun maybeRefreshAnalytics(account: String) = maybeRefresh(account, DashboardDataSource.POSTS)
    fun maybeRefreshStreak(account: String) = maybeRefresh(account, DashboardDataSource.ACTIVITY)
    fun maybeRefreshTopFollowers(account: String) = maybeRefresh(account, DashboardDataSource.TOP_FOLLOWERS)

    fun updateRefreshIndicator() {
        activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing =
            accountKey(activity.selectedAccount) in explicitAccounts
    }

    fun postUiIfCurrent(lifecycleToken: Long, action: () -> Unit) {
        activity.runOnUiThread {
            if (lifecycleToken != lifecycleGeneration || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            action()
        }
    }
}
