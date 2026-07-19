package com.tjg.twidget.schedule

import android.content.Context
import com.tjg.twidget.data.TwidgetStore

data class ScheduleCoordinatorResult(
    val post: ScheduledPost,
    val errors: List<String> = emptyList(),
    val fellBackToLocal: Boolean = false,
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

class ScheduleCoordinator(
    context: Context,
    private val store: ScheduleStore = ScheduleStore(context),
    private val localScheduler: LocalReminderScheduler = LocalReminderScheduler(context),
    private val bufferClient: BufferClient = BufferClient(context),
) {
    private val appContext = context.applicationContext
    private val mediaUploader = BufferMediaUploader(context, bufferClient)

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

    fun saveDraftWithProvider(
        post: ScheduledPost,
        nowMillis: Long = System.currentTimeMillis(),
    ): ScheduleCoordinatorResult {
        if (post.provider == ScheduleProvider.LOCAL_REMINDER) {
            return ScheduleCoordinatorResult(saveDraft(post, nowMillis))
        }
        val prepared = mediaUploader.withUploadedMedia(post)
        val readyPost = prepared.post
        val result: BufferResult<String> = if (readyPost != null) {
            bufferClient.saveDraft(readyPost)
        } else {
            BufferResult(errors = prepared.errors.map(::BufferError))
        }
        if (result.isSuccess && readyPost != null) {
            val draft = readyPost.copy(
                status = ScheduleStatus.DRAFT,
                remotePostId = result.value ?: readyPost.remotePostId,
                remoteSubmissionId = null,
                errorMessage = null,
                updatedAt = nowMillis,
            )
            BufferPublishCheckWorker.cancel(appContext, draft.id)
            store.upsert(draft)
            return ScheduleCoordinatorResult(draft)
        }
        if (!post.remotePostId.isNullOrBlank()) {
            return persistFailure(post, result.errors.map { it.message }, nowMillis)
        }
        val localUsername = post.accountId?.takeIf(String::isNotBlank)
            ?: TwidgetStore.settings(appContext).username
        val local = saveDraft(
            post.copy(
                provider = ScheduleProvider.LOCAL_REMINDER,
                accountUsername = localUsername.trim().trimStart('@'),
                remotePostId = null,
                remoteSubmissionId = null,
            ),
            nowMillis,
        )
        return ScheduleCoordinatorResult(local, fellBackToLocal = true)
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
            ScheduleProvider.BUFFER -> scheduleBuffer(post, nowMillis)
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
            ScheduleProvider.BUFFER -> {
                val remoteId = post.remotePostId
                if (remoteId.isNullOrBlank()) {
                    val cancelled = store.cancel(post.id, nowMillis) ?: post
                    ScheduleCoordinatorResult(cancelled)
                } else {
                    val remote = bufferClient.deletePost(remoteId)
                    if (remote.isSuccess) {
                        BufferPublishCheckWorker.cancel(appContext, post.id)
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

    private fun scheduleBuffer(post: ScheduledPost, nowMillis: Long): ScheduleCoordinatorResult {
        store.get(post.id)?.takeIf { it.provider == ScheduleProvider.LOCAL_REMINDER }?.let {
            localScheduler.cancel(it.id)
            ScheduleNotificationHelper.cancel(appContext, it.id)
        }
        val prepared = mediaUploader.withUploadedMedia(post)
        val readyPost = prepared.post
        val result: BufferResult<String> = when {
            readyPost == null -> BufferResult(errors = prepared.errors.map(::BufferError))
            readyPost.remotePostId.isNullOrBlank() -> bufferClient.schedulePost(readyPost)
            else -> bufferClient.updatePost(readyPost)
        }
        if (!result.isSuccess) {
            if (post.remotePostId.isNullOrBlank()) {
                val localUsername = post.accountId?.takeIf(String::isNotBlank)
                    ?: TwidgetStore.settings(appContext).username
                val local = post.copy(
                    provider = ScheduleProvider.LOCAL_REMINDER,
                    accountUsername = localUsername.trim().trimStart('@'),
                    remotePostId = null,
                    remoteSubmissionId = null,
                    errorMessage = null,
                )
                val fallback = scheduleLocal(local, nowMillis)
                if (fallback.isSuccess) return fallback.copy(fellBackToLocal = true)
            }
            return persistFailure(post, result.errors.map { it.message }, nowMillis)
        }
        val submitted = readyPost ?: post
        val scheduled = submitted.copy(
            status = ScheduleStatus.SCHEDULED,
            remotePostId = result.value ?: submitted.remotePostId,
            remoteSubmissionId = null,
            errorMessage = null,
            pinned = false,
            updatedAt = nowMillis,
        )
        store.upsert(scheduled)
        BufferPublishCheckWorker.enqueue(appContext, scheduled)
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
