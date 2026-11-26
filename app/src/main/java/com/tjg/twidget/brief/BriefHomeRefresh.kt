package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.data.TwidgetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.util.Log

/**
 * Refreshes the default account's brief when the dashboard opens, using the
 * same freshness gate as opening the brief itself. Generation is not forced.
 */
object BriefHomeRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generatingState = MutableStateFlow(false)
    private var job: Job? = null
    private var run = 0

    val generating: StateFlow<Boolean> = generatingState.asStateFlow()
    private var previewing = false

    fun showsAnimation(context: Context): Boolean =
        generatingState.value && (previewing || BriefSettingsStore.refreshOnHome(context))

    /** Shows the home-card generation animation without calling a provider. */
    @Synchronized
    fun preview(durationMs: Long = 8_000) {
        val id = synchronized(this) {
            job?.cancel()
            ++run
        }
        job = scope.launch {
            previewing = true
            generatingState.value = true
            try {
                delay(durationMs)
            } finally {
                if (id == run) {
                    previewing = false
                    generatingState.value = false
                }
            }
        }
    }

    @Synchronized
    fun startIfNeeded(context: Context) {
        val app = context.applicationContext
        if (!BriefSettingsStore.refreshOnHome(app) ||
            !BriefSettingsStore.enabled(app) ||
            !BriefSettingsStore.onboardingComplete(app)
        ) return
        val account = TwidgetStore.settings(app).username.trim().trimStart('@')
        if (account.isBlank()) return
        val contentChanged = BriefSettingsStore.contentRegenerationPending(app)
        val id = synchronized(this) {
            if (job?.isActive == true) return
            ++run
        }
        job = scope.launch {
            try {
                // Always re-check the saved run: a completed attempt is not a freshness guarantee.
                val source = BriefEngine.rebuild(app, account)
                val ready = source.hasGeneratedNarrative &&
                    BriefAiCachePolicy.isFresh(source) &&
                    BriefRunCache.providerMatches(BriefSettingsStore.provider(app), source.providerUsed)
                if ((ready || BriefGenerationCadence.isCovered(app, source)) && !contentChanged) return@launch
                generatingState.value = true
                val input = if (contentChanged) BriefEngine.rebuild(app, account, force = true) else source
                val result = BriefAiCoordinator.enrich(app, input, force = false)
                if (contentChanged && result.snapshot.hasGeneratedNarrative &&
                    BriefAiCachePolicy.isFresh(result.snapshot)) {
                    BriefSettingsStore.clearContentRegenerationPending(app)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A failed background refresh must remain retryable and leave the dashboard usable.
                Log.w("BriefHomeRefresh", "Background refresh failed", failure)
            } finally {
                if (id == run) generatingState.value = false
            }
        }
    }
}
