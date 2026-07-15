package com.tjg.twidget.schedule

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri

class ScheduleStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun create(post: ScheduledPost): ScheduledPost = synchronized(lock) {
        val posts = readAllLocked().toMutableList()
        require(posts.none { it.id == post.id }) { "A schedule with this ID already exists" }
        posts += post
        writeAllLocked(posts)
        post
    }

    fun upsert(post: ScheduledPost): ScheduledPost = synchronized(lock) {
        val posts = readAllLocked().toMutableList()
        val previous = posts.toList()
        val index = posts.indexOfFirst { it.id == post.id }
        if (index < 0) {
            posts += post
        } else {
            posts[index] = post
        }
        writeAllLocked(posts)
        releaseUnusedLocalMedia(previous, posts)
        post
    }

    fun get(id: String): ScheduledPost? = synchronized(lock) {
        readAllLocked().firstOrNull { it.id == id }
    }

    fun list(): List<ScheduledPost> = synchronized(lock) {
        readAllLocked().sortedBy { it.scheduledAt ?: Long.MAX_VALUE }
    }

    fun listForAccount(account: String): List<ScheduledPost> {
        val normalized = normalizeAccount(account)
        return synchronized(lock) {
            readAllLocked()
                .filter {
                    normalizeAccount(it.accountId.orEmpty()) == normalized ||
                        normalizeAccount(it.accountUsername) == normalized
                }
                .sortedBy { it.scheduledAt ?: Long.MAX_VALUE }
        }
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val posts = readAllLocked()
        val removed = posts.firstOrNull { it.id == id } ?: return@synchronized false
        val remaining = posts.filterNot { it.id == id }
        writeAllLocked(remaining)
        releaseUnusedLocalMedia(posts, remaining)
        ScheduleChecklistProgress.clear(appContext, removed.id)
        true
    }

    fun removeForAccount(account: String): List<ScheduledPost> = synchronized(lock) {
        val normalized = normalizeAccount(account)
        val posts = readAllLocked()
        val removed = posts.filter {
            normalizeAccount(it.accountId.orEmpty()) == normalized ||
                normalizeAccount(it.accountUsername) == normalized
        }
        if (removed.isEmpty()) return@synchronized emptyList()
        val remaining = posts - removed.toSet()
        writeAllLocked(remaining)
        releaseUnusedLocalMedia(posts, remaining)
        removed.forEach {
            ScheduleChecklistProgress.clear(appContext, it.id)
        }
        removed
    }

    fun cancel(id: String, nowMillis: Long = System.currentTimeMillis()): ScheduledPost? = synchronized(lock) {
        val posts = readAllLocked().toMutableList()
        val index = posts.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val cancelled = posts[index].copy(
            status = ScheduleStatus.CANCELLED,
            updatedAt = nowMillis,
            errorMessage = null,
        )
        posts[index] = cancelled
        writeAllLocked(posts)
        cancelled
    }

    private fun readAllLocked(): List<ScheduledPost> {
        val raw = runCatching { preferences.getString(KEY_POSTS, null) }.getOrNull()
            ?: return emptyList()
        return runCatching { ScheduleJsonCodec.decodeList(raw) }.getOrElse {
            if (!preferences.contains(KEY_CORRUPT_BACKUP)) {
                preferences.edit().putString(KEY_CORRUPT_BACKUP, raw).commit()
            }
            emptyList()
        }
    }

    private fun writeAllLocked(posts: List<ScheduledPost>) {
        check(preferences.edit().putString(KEY_POSTS, ScheduleJsonCodec.encodeList(posts)).commit()) {
            "Unable to persist schedules"
        }
    }

    private fun normalizeAccount(account: String): String =
        account.trim().trimStart('@').lowercase()

    private fun releaseUnusedLocalMedia(
        previous: Collection<ScheduledPost>,
        next: Collection<ScheduledPost>,
    ) {
        val retained = next.flatMap { it.localMediaUris() }.toSet()
        previous.flatMap { it.localMediaUris() }
            .toSet()
            .filterNot(retained::contains)
            .forEach(::releaseUri)
    }

    private fun ScheduledPost.localMediaUris(): Set<String> =
        thread.flatMap { item -> item.media.filterIsInstance<LocalUriMedia>().map(LocalUriMedia::uri) }.toSet()

    private fun releaseUri(value: String) {
        if (ScheduleOwnedMedia.delete(appContext, value)) return
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(value),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    companion object {
        const val PREFS_NAME = "twidget_schedules"
        private const val KEY_POSTS = "posts"
        private const val KEY_CORRUPT_BACKUP = "posts_corrupt_backup"
        private val lock = Any()
    }
}
