package com.tjg.twidget.schedule

import com.tjg.twidget.data.TwidgetStore

data class PostponeSyncResult(
    val imported: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

class PostponeScheduleSync(
    context: android.content.Context,
    private val store: ScheduleStore = ScheduleStore(context),
    private val client: PostponeClient = PostponeClient(context),
) {
    private val appContext = context.applicationContext

    fun sync(): PostponeSyncResult {
        if (ScheduleSettingsStore.defaultProvider(appContext) != ScheduleProvider.POSTPONE) {
            return PostponeSyncResult()
        }
        val trackedUsername = TwidgetStore.settings(appContext).username.trim().trimStart('@')
        if (trackedUsername.isBlank()) {
            return PostponeSyncResult(errors = listOf("No default X account is configured"))
        }
        val accounts = client.listTwitterSocialAccounts()
        if (!accounts.isSuccess) return PostponeSyncResult(errors = accounts.errors.map { it.message })
        val connected = accounts.value.orEmpty().filter { it.isConnected && it.isEnabled }
        val mappedUsername = ScheduleSettingsStore.postponeAccountFor(appContext, trackedUsername)
        val account = connected.firstOrNull {
            ScheduleAccountMapping.normalize(it.username) == ScheduleAccountMapping.normalize(mappedUsername.orEmpty())
        } ?: connected.singleOrNull()
            ?: connected.firstOrNull {
                ScheduleAccountMapping.normalize(it.username) == ScheduleAccountMapping.normalize(trackedUsername)
            }
            ?: return PostponeSyncResult(errors = listOf("Choose the default Postpone X account in Settings"))

        if (mappedUsername.isNullOrBlank()) {
            ScheduleSettingsStore.setPostponeAccount(appContext, trackedUsername, account.username)
        }

        val existing = store.listForAccount(trackedUsername).filter { it.provider == ScheduleProvider.POSTPONE }
        data class FetchSpec(
            val publishingStatus: PostponePublishingStatus,
            val submissionType: PostponeSubmissionType,
            val localStatus: ScheduleStatus,
            val importNew: Boolean = true,
            val startDateMillis: Long? = null,
        )
        val confirmationStart = existing
            .filter { it.status == ScheduleStatus.SCHEDULED && it.remoteSubmissionId != null }
            .mapNotNull(ScheduledPost::scheduledAt)
            .minOrNull()
            ?.minus(24 * 60 * 60 * 1000L)
        val specs = buildList {
            add(FetchSpec(
                PostponePublishingStatus.READY_TO_PUBLISH,
                PostponeSubmissionType.SCHEDULED,
                ScheduleStatus.SCHEDULED,
            ))
            add(FetchSpec(
                PostponePublishingStatus.DRAFT,
                PostponeSubmissionType.ALL,
                ScheduleStatus.DRAFT,
            ))
        }.toMutableList().apply {
            if (confirmationStart != null) {
                add(FetchSpec(
                    PostponePublishingStatus.READY_TO_PUBLISH,
                    PostponeSubmissionType.FAILED,
                    ScheduleStatus.NEEDS_ACTION,
                    importNew = false,
                    startDateMillis = confirmationStart,
                ))
                add(FetchSpec(
                    PostponePublishingStatus.READY_TO_PUBLISH,
                    PostponeSubmissionType.SUBMITTED,
                    ScheduleStatus.PUBLISHED,
                    importNew = false,
                    startDateMillis = confirmationStart,
                ))
            }
        }
        val fetched = mutableListOf<Pair<FetchSpec, PostponeSubmission>>()
        for (spec in specs) {
            var page = 1
            var total = Int.MAX_VALUE
            while (fetched.count { it.first == spec } < total) {
                val result = client.listTwitterSubmissions(
                    account.id,
                    spec.publishingStatus,
                    spec.submissionType,
                    page,
                    startDateMillis = spec.startDateMillis,
                )
                if (!result.isSuccess) return PostponeSyncResult(errors = result.errors.map { it.message })
                val value = result.value ?: break
                total = value.total
                fetched += value.submissions.map { spec to it }
                if (value.submissions.isEmpty()) break
                page++
            }
        }

        val now = System.currentTimeMillis()
        var imported = 0
        var updated = 0
        val seenSubmissionIds = mutableSetOf<String>()
        fetched.forEach { (spec, submission) ->
            seenSubmissionIds += submission.id
            val current = existing.firstOrNull { it.remoteSubmissionId == submission.id }
                ?: existing.firstOrNull { it.matches(submission, account.username) }
            if (current == null && !spec.importNew) return@forEach
            val status = spec.localStatus
            val local = ScheduledPost(
                id = current?.id ?: remoteLocalId(submission.id),
                provider = ScheduleProvider.POSTPONE,
                status = status,
                accountId = trackedUsername,
                accountUsername = account.username,
                scheduledAt = submission.postAt,
                thread = current?.thread?.takeIf { current.remotePostId != null }
                    ?: listOf(ScheduleThreadItem(id = "postpone-item-${submission.id}", text = submission.text)),
                remotePostId = current?.remotePostId,
                remoteSubmissionId = submission.id,
                errorMessage = submission.errorMessage,
                createdAt = current?.createdAt ?: now,
                updatedAt = now,
                publishedAt = submission.submittedAt ?: current?.publishedAt,
                pinned = current?.pinned ?: false,
                deletedAt = current?.deletedAt,
            )
            store.upsert(local)
            if (status == ScheduleStatus.SCHEDULED) PostponePublishCheckWorker.enqueue(appContext, local)
            if (current == null) imported++ else updated++
        }

        var removed = 0
        existing.filter {
            it.remoteSubmissionId != null &&
                it.remotePostId == null &&
                it.status != ScheduleStatus.NEEDS_ACTION &&
                it.remoteSubmissionId !in seenSubmissionIds
        }.forEach {
            if (store.remove(it.id)) removed++
        }
        return PostponeSyncResult(imported, updated, removed)
    }

    private fun ScheduledPost.matches(submission: PostponeSubmission, remoteUsername: String): Boolean {
        if (!accountUsername.equals(remoteUsername, ignoreCase = true)) return false
        if (thread.firstOrNull()?.text != submission.text) return false
        val localTime = scheduledAt
        val remoteTime = submission.postAt
        return localTime == remoteTime || (
            localTime != null && remoteTime != null && kotlin.math.abs(localTime - remoteTime) < 60_000L
        )
    }

    companion object {
        internal fun remoteLocalId(submissionId: String): String = "postpone-submission-$submissionId"
    }
}
