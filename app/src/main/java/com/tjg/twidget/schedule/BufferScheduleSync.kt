package com.tjg.twidget.schedule

import com.tjg.twidget.data.TwidgetStore

data class BufferSyncResult(
    val imported: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = errors.isEmpty()
}

class BufferScheduleSync(
    context: android.content.Context,
    private val store: ScheduleStore = ScheduleStore(context),
    private val client: BufferClient = BufferClient(context),
) {
    private val appContext = context.applicationContext

    fun sync(userInitiated: Boolean = false): BufferSyncResult {
        if (!BufferOAuth.isConnected(appContext)) {
            return BufferSyncResult()
        }
        BufferRequestThrottle.blockingMessage(appContext)?.let {
            return BufferSyncResult(errors = listOf(it))
        }
        if (!BufferRequestThrottle.beginSync(appContext, userInitiated)) return BufferSyncResult()
        val trackedUsername = TwidgetStore.settings(appContext).username.trim().trimStart('@')
        if (trackedUsername.isBlank()) return BufferSyncResult(errors = listOf("No default X account is configured"))
        val mappedId = ScheduleSettingsStore.bufferChannelFor(appContext, trackedUsername)
        val mappedOrganizationId = ScheduleSettingsStore.bufferOrganizationFor(appContext, trackedUsername)
        val channelId: String
        val organizationId: String
        if (!mappedId.isNullOrBlank() && !mappedOrganizationId.isNullOrBlank()) {
            channelId = mappedId
            organizationId = mappedOrganizationId
        } else {
            val channels = client.listTwitterChannels()
            if (!channels.isSuccess) return BufferSyncResult(errors = channels.errors.map { it.message })
            val connected = channels.value.orEmpty()
            val channel = connected.firstOrNull { it.id == mappedId }
                ?: connected.singleOrNull()
                ?: connected.firstOrNull {
                    ScheduleAccountMapping.normalize(it.name) == ScheduleAccountMapping.normalize(trackedUsername) ||
                        ScheduleAccountMapping.normalize(it.displayName.orEmpty()) == ScheduleAccountMapping.normalize(trackedUsername)
                }
                ?: return BufferSyncResult(errors = listOf("Choose the default Buffer X channel in Settings"))
            ScheduleSettingsStore.setBufferChannel(appContext, trackedUsername, channel)
            channelId = channel.id
            organizationId = channel.organizationId
        }

        val existing = store.list().filter {
            it.provider == ScheduleProvider.BUFFER && it.accountUsername == channelId
        }
        val active = client.listPosts(organizationId, channelId, listOf("scheduled", "draft"))
        if (!active.isSuccess) return BufferSyncResult(errors = active.errors.map { it.message })
        val now = System.currentTimeMillis()
        val confirmationStart = existing
            .filter {
                it.status == ScheduleStatus.SCHEDULED &&
                    it.scheduledAt?.let { dueAt -> dueAt <= now + TERMINAL_LOOKAHEAD_MS } == true
            }
            .mapNotNull(ScheduledPost::scheduledAt)
            .minOrNull()
            ?.minus(24 * 60 * 60 * 1000L)
        val terminal = confirmationStart?.let {
            client.listPosts(organizationId, channelId, listOf("sent", "error"), it)
        } ?: BufferResult(emptyList())
        if (!terminal.isSuccess) return BufferSyncResult(errors = terminal.errors.map { it.message })
        val remotePosts = active.value.orEmpty() + terminal.value.orEmpty()
        var imported = 0
        var updated = 0
        val seen = mutableSetOf<String>()
        remotePosts.forEach { bufferPost ->
            seen += bufferPost.id
            val current = existing.firstOrNull { it.remotePostId == bufferPost.id }
                ?: existing.firstOrNull { it.matches(bufferPost, channelId) }
            val status = when (bufferPost.status.lowercase()) {
                "draft" -> ScheduleStatus.DRAFT
                "scheduled", "sending" -> ScheduleStatus.SCHEDULED
                "sent" -> ScheduleStatus.PUBLISHED
                "error" -> ScheduleStatus.NEEDS_ACTION
                else -> return@forEach
            }
            val local = ScheduledPost(
                id = current?.id ?: remoteLocalId(bufferPost.id),
                provider = ScheduleProvider.BUFFER,
                status = status,
                accountId = trackedUsername,
                accountUsername = channelId,
                scheduledAt = bufferPost.dueAt,
                thread = mergeBufferThread(current, bufferPost),
                remotePostId = bufferPost.id,
                remoteSubmissionId = null,
                errorMessage = if (status == ScheduleStatus.NEEDS_ACTION) "Buffer could not publish this post" else null,
                createdAt = current?.createdAt ?: bufferPost.createdAt ?: now,
                updatedAt = now,
                publishedAt = if (status == ScheduleStatus.PUBLISHED) bufferPost.dueAt ?: current?.publishedAt else current?.publishedAt,
                pinned = current?.pinned ?: false,
                deletedAt = current?.deletedAt,
            )
            store.upsert(local)
            if (ScheduleNotificationPolicy.shouldNotifyBufferPublished(current, status)) {
                ScheduleNotificationHelper.showBufferPublished(appContext, local)
            }
            if (ScheduleNotificationPolicy.shouldNotifyBufferFailed(current, status)) {
                ScheduleNotificationHelper.showBufferFailed(appContext, local)
            }
            if (status == ScheduleStatus.SCHEDULED) BufferPublishCheckWorker.enqueue(appContext, local)
            if (current == null) imported++ else updated++
        }

        var removed = 0
        existing.filter {
            shouldRemoveMissing(it, seen, now)
        }.forEach {
            if (store.remove(it.id)) removed++
        }
        return BufferSyncResult(imported, updated, removed)
    }

    private fun ScheduledPost.matches(remote: BufferPost, channelId: String): Boolean {
        if (accountUsername != channelId || thread.firstOrNull()?.text != remote.text) return false
        return scheduledAt == remote.dueAt || (
            scheduledAt != null && remote.dueAt != null && kotlin.math.abs(scheduledAt - remote.dueAt) < 60_000L
        )
    }

    companion object {
        private const val TERMINAL_LOOKAHEAD_MS = 5 * 60 * 1000L
        internal fun remoteLocalId(postId: String): String = "buffer-post-$postId"

        /**
         * A just-due post can briefly disappear from Buffer's active list before it
         * appears in the sent/error list. Keep it locally so the publish checker can
         * retry and observe the terminal transition instead of losing its notification.
         */
        internal fun shouldRemoveMissing(post: ScheduledPost, seen: Set<String>, now: Long): Boolean {
            val remoteId = post.remotePostId ?: return false
            if (remoteId in seen) return false
            return when (post.status) {
                ScheduleStatus.DRAFT -> true
                ScheduleStatus.SCHEDULED ->
                    post.scheduledAt?.let { it > now + TERMINAL_LOOKAHEAD_MS } == true
                else -> false
            }
        }
    }
}

/**
 * Keeps locally authored media authoritative while letting posts originally
 * imported from Buffer refresh their remote media metadata. The refresh heals
 * records created by older builds that discarded Buffer's thumbnail URL.
 */
internal fun mergeBufferThread(
    current: ScheduledPost?,
    remote: BufferPost,
): List<ScheduleThreadItem> {
    if (remote.thread.isNotEmpty()) {
        return remote.thread.mapIndexed { index, remoteItem ->
            val localItem = current?.thread?.getOrNull(index)
            val localMedia = localItem?.media.orEmpty()
            val hasUnsavedLocalMedia = localMedia.any { it is LocalUriMedia }
            ScheduleThreadItem(
                id = localItem?.id ?: "buffer-item-${remote.id}-$index",
                text = remoteItem.text,
                media = if (hasUnsavedLocalMedia) localMedia else remoteItem.media,
            )
        }
    }
    val thread = current?.thread ?: listOf(
        ScheduleThreadItem(id = "buffer-item-${remote.id}", text = remote.text),
    )
    if (remote.media.isEmpty() || thread.size != 1) return thread
    val existingMedia = thread.first().media
    val importedRecord = current?.id == BufferScheduleSync.remoteLocalId(remote.id)
    val canRefreshImportedMedia = importedRecord && existingMedia.all { it is PublicUrlMedia }
    if (existingMedia.isNotEmpty() && !canRefreshImportedMedia) return thread
    return listOf(thread.first().copy(media = remote.media))
}
