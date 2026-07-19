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
                thread = mergedThread(current, bufferPost),
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
            if (status == ScheduleStatus.SCHEDULED) BufferPublishCheckWorker.enqueue(appContext, local)
            if (current == null) imported++ else updated++
        }

        var removed = 0
        existing.filter {
            it.status in setOf(ScheduleStatus.SCHEDULED, ScheduleStatus.DRAFT) &&
                it.remotePostId != null && it.remotePostId !in seen
        }.forEach {
            if (store.remove(it.id)) removed++
        }
        return BufferSyncResult(imported, updated, removed)
    }

    /**
     * The local thread stays the richer record once it exists, but media
     * added on Buffer's side (for example from the web dashboard) is pulled
     * in whenever the local copy has none. Multi-item local threads are left
     * untouched because Buffer reports assets per post, not per thread item.
     */
    private fun mergedThread(current: ScheduledPost?, remote: BufferPost): List<ScheduleThreadItem> {
        val thread = current?.thread ?: listOf(
            ScheduleThreadItem(id = "buffer-item-${remote.id}", text = remote.text)
        )
        if (remote.media.isEmpty() || thread.size != 1 || thread.first().media.isNotEmpty()) return thread
        return listOf(thread.first().copy(media = remote.media))
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
    }
}
