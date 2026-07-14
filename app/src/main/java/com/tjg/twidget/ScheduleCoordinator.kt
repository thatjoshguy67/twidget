package com.tjg.twidget

import android.content.Context

data class ScheduleCoordinatorResult(
    val post: ScheduledPost,
    val errors: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

class ScheduleCoordinator(
    context: Context,
    private val store: ScheduleStore = ScheduleStore(context),
    private val localScheduler: LocalReminderScheduler = LocalReminderScheduler(context),
    private val postponeClient: PostponeClient = PostponeClient(context),
) {
    private val appContext = context.applicationContext

    fun saveDraft(post: ScheduledPost, nowMillis: Long = System.currentTimeMillis()): ScheduledPost {
        val previous = store.get(post.id)
        if (previous?.provider == ScheduleProvider.LOCAL_REMINDER &&
            previous.status in setOf(ScheduleStatus.SCHEDULED, ScheduleStatus.NEEDS_ACTION)
        ) {
            localScheduler.cancel(previous.id)
            ScheduleNotificationHelper.cancel(appContext, previous.id)
        }
        val draft = post.copy(
            status = ScheduleStatus.DRAFT,
            errorMessage = null,
            updatedAt = nowMillis,
        )
        return store.upsert(draft)
    }

    fun schedule(post: ScheduledPost, nowMillis: Long = System.currentTimeMillis()): ScheduleCoordinatorResult {
        val account = post.accountId?.takeIf(String::isNotBlank) ?: post.accountUsername
        val maxTextLength = SchedulePolicy.textLimit(TwidgetStore.currentStats(appContext, account).isVerified)
        val issues = SchedulePolicy.validate(post, nowMillis, maxTextLength)
        if (issues.isNotEmpty()) {
            store.get(post.id)?.takeIf {
                it.provider == ScheduleProvider.LOCAL_REMINDER &&
                    it.status in setOf(ScheduleStatus.SCHEDULED, ScheduleStatus.NEEDS_ACTION)
            }?.let {
                localScheduler.cancel(it.id)
                ScheduleNotificationHelper.cancel(appContext, it.id)
            }
            val message = issues.joinToString(" ") { it.message }
            val editableDraft = post.copy(
                status = ScheduleStatus.DRAFT,
                errorMessage = message,
                updatedAt = nowMillis,
            )
            store.upsert(editableDraft)
            return ScheduleCoordinatorResult(editableDraft, issues.map { it.message })
        }
        return when (post.provider) {
            ScheduleProvider.LOCAL_REMINDER -> scheduleLocal(post, nowMillis)
            ScheduleProvider.POSTPONE -> schedulePostpone(post, nowMillis)
        }
    }

    fun cancel(scheduleId: String, nowMillis: Long = System.currentTimeMillis()): ScheduleCoordinatorResult? {
        val post = store.get(scheduleId) ?: return null
        return when (post.provider) {
            ScheduleProvider.LOCAL_REMINDER -> {
                localScheduler.cancel(post.id)
                ScheduleNotificationHelper.cancel(appContext, post.id)
                val cancelled = store.cancel(post.id, nowMillis) ?: post
                ScheduleCoordinatorResult(cancelled)
            }
            ScheduleProvider.POSTPONE -> {
                val remoteId = post.remotePostId
                if (remoteId.isNullOrBlank()) {
                    val cancelled = store.cancel(post.id, nowMillis) ?: post
                    ScheduleCoordinatorResult(cancelled)
                } else {
                    val remote = postponeClient.cancelScheduledTweet(remoteId)
                    if (remote.isSuccess) {
                        val cancelled = store.cancel(post.id, nowMillis) ?: post
                        ScheduleCoordinatorResult(cancelled)
                    } else {
                        persistFailure(post, remote.errors.map { it.message }, nowMillis)
                    }
                }
            }
        }
    }

    private fun scheduleLocal(post: ScheduledPost, nowMillis: Long): ScheduleCoordinatorResult {
        localScheduler.cancel(post.id)
        ScheduleNotificationHelper.cancel(appContext, post.id)
        val scheduled = post.copy(
            status = ScheduleStatus.SCHEDULED,
            errorMessage = null,
            pinned = false,
            updatedAt = nowMillis,
        )
        store.upsert(scheduled)
        return try {
            localScheduler.schedule(scheduled)
            ScheduleCoordinatorResult(scheduled)
        } catch (error: Exception) {
            persistFailure(scheduled, listOf(error.message ?: "Unable to schedule reminder"), nowMillis)
        }
    }

    private fun schedulePostpone(post: ScheduledPost, nowMillis: Long): ScheduleCoordinatorResult {
        store.get(post.id)?.takeIf { it.provider == ScheduleProvider.LOCAL_REMINDER }?.let {
            localScheduler.cancel(it.id)
            ScheduleNotificationHelper.cancel(appContext, it.id)
        }
        val result = if (post.remotePostId.isNullOrBlank()) {
            postponeClient.scheduleTweet(post)
        } else {
            postponeClient.updateScheduledTweet(post)
        }
        if (!result.isSuccess) {
            return persistFailure(post, result.errors.map { it.message }, nowMillis)
        }
        val scheduled = post.copy(
            status = ScheduleStatus.SCHEDULED,
            remotePostId = result.value?.remotePostId ?: post.remotePostId,
            errorMessage = null,
            pinned = false,
            updatedAt = nowMillis,
        )
        store.upsert(scheduled)
        return ScheduleCoordinatorResult(scheduled)
    }

    private fun persistFailure(
        post: ScheduledPost,
        errors: List<String>,
        nowMillis: Long,
    ): ScheduleCoordinatorResult {
        val messages = errors.filter(String::isNotBlank).ifEmpty { listOf("Scheduling failed") }
        val failed = post.copy(
            status = ScheduleStatus.FAILED,
            errorMessage = messages.joinToString("\n"),
            pinned = false,
            updatedAt = nowMillis,
        )
        store.upsert(failed)
        return ScheduleCoordinatorResult(failed, messages)
    }
}
