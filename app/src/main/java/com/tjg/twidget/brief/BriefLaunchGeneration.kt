package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.schedule.BufferScheduleSync
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Owns the first-launch generation independently of either screen. This lets
 * the welcome page begin work immediately, while the Brief spinner can await
 * that exact same request if Continue is tapped before it finishes.
 */
object BriefLaunchGeneration {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Deferred<BriefAiResult>>()

    @Synchronized
    fun start(
        context: Context,
        username: String,
        restartIfComplete: Boolean,
    ): Deferred<BriefAiResult> {
        val account = username.trim().trimStart('@')
        if (account.isBlank()) {
            return CompletableDeferred<BriefAiResult>().also {
                it.completeExceptionally(IllegalArgumentException("A Brief account is required"))
            }
        }
        val key = account.lowercase(Locale.US)
        val current = jobs[key]
        if (current != null && (!current.isCompleted || !restartIfComplete)) return current

        val appContext = context.applicationContext
        return scope.async {
            runCatching {
                BufferScheduleSync(appContext).sync(userInitiated = true)
            }
            BriefStore.resetAi(appContext, account)
            val source = BriefEngine.rebuild(appContext, account, force = true)
            runCatching {
                BriefAiCoordinator.enrich(appContext, source, force = true)
            }.getOrElse {
                BriefAiResult(source, BriefLocalStatus.UNAVAILABLE)
            }
        }.also { jobs[key] = it }
    }

    @Synchronized
    fun current(username: String): Deferred<BriefAiResult>? =
        jobs[username.trim().trimStart('@').lowercase(Locale.US)]
}

enum class BriefContinueDestination { SPINNER, BRIEF }

internal fun briefContinueDestination(generationComplete: Boolean): BriefContinueDestination =
    if (generationComplete) BriefContinueDestination.BRIEF else BriefContinueDestination.SPINNER
