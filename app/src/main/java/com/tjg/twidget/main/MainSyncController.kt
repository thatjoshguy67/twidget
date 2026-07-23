package com.tjg.twidget.main

import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tjg.twidget.R
import com.tjg.twidget.analytics.ActivityClient
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.RettiwtClient
import com.tjg.twidget.ui.OneUiSpinner
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.layout.NavDrawerLayout
import java.util.Locale

internal class MainSyncController(
    private val activity: MainActivity,
) {
    var isSyncing = false
        private set
    var lifecycleGeneration = 0L
        private set
    private var syncGeneration = 0L
    private val analyticsInFlight = mutableSetOf<String>()
    private val streakInFlight = mutableSetOf<String>()

    fun invalidateLifecycle() {
        lifecycleGeneration++
    }

    fun setupRefresh() {
        activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).apply {
            OneUiSpinner.attachToSwipeRefresh(this)
            setOnRefreshListener { handlePullRefresh() }
        }
    }

    fun handlePullRefresh() {
        val toolbarLayout = activity.findViewById<NavDrawerLayout>(R.id.main_toolbar_layout)
        if (!toolbarLayout.isExpanded) {
            toolbarLayout.setExpanded(true, animate = true)
            activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing = false
            return
        }
        sync()
    }

    fun sync() {
        if (isSyncing) {
            activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing = false
            return
        }
        val account = activity.selectedAccount.ifBlank { TwidgetStore.settings(activity).username }
        isSyncing = true
        val generation = ++syncGeneration
        val lifecycleToken = lifecycleGeneration
        // Launch-time syncs show the same spinner as a pull refresh.
        activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing = true
        AppExecutors.execute(onRejected = {
            postUiIfCurrent(lifecycleToken) {
                if (generation != syncGeneration) return@postUiIfCurrent
                isSyncing = false
                activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing = false
                Toast.makeText(activity, R.string.sync_failed, Toast.LENGTH_SHORT).show()
            }
        }) {
            val appContext = activity.applicationContext
            val result = runCatching {
                val stats = RettiwtClient.refresh(appContext, account)
                TwidgetStore.saveStats(appContext, stats)
                TwidgetWidget.updateAll(appContext)
                stats
            }
            postUiIfCurrent(lifecycleToken) {
                if (generation != syncGeneration) return@postUiIfCurrent
                isSyncing = false
                activity.findViewById<SwipeRefreshLayout>(R.id.main_refresh).isRefreshing = false
                activity.render()
                if (result.isFailure) {
                    Toast.makeText(activity, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Fetches fresh post analytics off the main thread when the cache is stale,
    // then repaints the current account's dashboard.
    fun maybeRefreshAnalytics(account: String) {
        if (activity.editModeController.editMode || !AnalyticsClient.isStale(activity, activity.analytics)) return
        val key = account.lowercase(Locale.US)
        synchronized(analyticsInFlight) {
            if (!analyticsInFlight.add(key)) return
        }
        val lifecycleToken = lifecycleGeneration
        AppExecutors.execute(onRejected = {
            synchronized(analyticsInFlight) { analyticsInFlight.remove(key) }
        }) {
            val fresh = runCatching { AnalyticsClient.refresh(activity.applicationContext, account) }.getOrNull()
            synchronized(analyticsInFlight) { analyticsInFlight.remove(key) }
            fresh ?: return@execute
            postUiIfCurrent(lifecycleToken) {
                if (activity.selectedAccount.equals(account, ignoreCase = true) && !activity.editModeController.editMode) {
                    activity.analytics = fresh
                    activity.dashboardBinder.bindContent()
                }
            }
        }
    }

    fun maybeRefreshStreak(account: String) {
        if (activity.editModeController.editMode || !ActivityClient.isStale(activity, account)) return
        val key = account.lowercase(Locale.US)
        synchronized(streakInFlight) {
            if (!streakInFlight.add(key)) return
        }
        val lifecycleToken = lifecycleGeneration
        AppExecutors.execute(onRejected = {
            synchronized(streakInFlight) { streakInFlight.remove(key) }
        }) {
            runCatching { ActivityClient.refresh(activity.applicationContext, account) }
            synchronized(streakInFlight) { streakInFlight.remove(key) }
            postUiIfCurrent(lifecycleToken) {
                if (activity.selectedAccount.equals(account, ignoreCase = true) && !activity.editModeController.editMode) {
                    activity.dashboardBinder.bindContent()
                }
            }
        }
    }

    fun postUiIfCurrent(lifecycleToken: Long, action: () -> Unit) {
        activity.runOnUiThread {
            if (lifecycleToken != lifecycleGeneration || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            action()
        }
    }
}
