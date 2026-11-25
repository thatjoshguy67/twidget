package com.tjg.twidget.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal enum class DashboardDataSource { PROFILE, POSTS, ACTIVITY, TOP_FOLLOWERS }
internal enum class DashboardFetchState { LOADING, UPDATED, UNAVAILABLE, FAILED }
internal data class DashboardFetchStatus(val state: DashboardFetchState, val attemptedAt: Long)

/** Account/source ownership is shared by automatic refreshes and explicit retries. */
internal class DashboardRefreshCoordinator(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private data class Key(val account: String, val source: DashboardDataSource)
    private val running = mutableMapOf<Key, Deferred<Result<Boolean>>>()
    private val statuses = mutableMapOf<Key, DashboardFetchStatus>()
    private fun key(account: String, source: DashboardDataSource) = Key(account.trim().trimStart('@').lowercase(java.util.Locale.US), source)
    @Synchronized fun status(account: String, source: DashboardDataSource) = statuses[key(account, source)]
    @Synchronized fun mayAttempt(account: String, source: DashboardDataSource): Boolean =
        statuses[key(account, source)]?.let { now() - it.attemptedAt >= 60_000L } ?: true
    @Synchronized fun request(account: String, source: DashboardDataSource, fetch: suspend () -> Boolean): Deferred<Result<Boolean>> {
        val key = key(account, source)
        running[key]?.takeIf { !it.isCompleted }?.let { return it }
        statuses[key] = DashboardFetchStatus(DashboardFetchState.LOADING, now())
        val task = scope.async(start = CoroutineStart.LAZY) {
            val result = try { Result.success(fetch()) } catch (error: Exception) {
                if (error is CancellationException) throw error
                Result.failure(error)
            }
            synchronized(this@DashboardRefreshCoordinator) {
                statuses[key] = DashboardFetchStatus(when {
                    result.isFailure -> DashboardFetchState.FAILED
                    result.getOrThrow() -> DashboardFetchState.UPDATED
                    else -> DashboardFetchState.UNAVAILABLE
                }, now())
            }
            result
        }
        running[key] = task
        task.invokeOnCompletion { synchronized(this) { if (running[key] === task) running.remove(key) } }
        task.start()
        return task
    }
}

internal fun dashboardRefreshOrder(accounts: List<String>, visible: String): List<String> {
    fun key(value: String) = value.trim().trimStart('@').lowercase(java.util.Locale.US)
    return (listOf(visible) + accounts).filter(String::isNotBlank).distinctBy(::key)
        .filter { candidate -> accounts.any { key(it) == key(candidate) } }
}
