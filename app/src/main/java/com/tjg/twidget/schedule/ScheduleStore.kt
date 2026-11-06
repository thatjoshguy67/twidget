package com.tjg.twidget.schedule

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri

/** Durable schedule mutations are serialized and committed once per transaction. */
class ScheduleStore(
    context: Context,
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
) {
    private val appContext = context.applicationContext
    private var cachedRaw: String? = null
    private var cachedPosts: List<ScheduledPost> = emptyList()
    private var loaded = false

    fun create(post: ScheduledPost): ScheduledPost = transaction { posts ->
        require(posts.none { it.id == post.id }) { "A schedule with this ID already exists" }
        posts += post
        post
    }

    fun upsert(post: ScheduledPost): ScheduledPost = transaction { posts ->
        val index = posts.indexOfFirst { it.id == post.id }
        if (index < 0) posts += post else posts[index] = post
        post
    }

    fun get(id: String): ScheduledPost? = snapshot().firstOrNull { it.id == id }
    fun list(): List<ScheduledPost> = ScheduleQueuePolicy.order(snapshot().filter { it.deletedAt == null })
    fun listTrash(): List<ScheduledPost> = snapshot().filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    fun listForAccount(account: String): List<ScheduledPost> = list().filter { it.belongsTo(account) }
    fun listTrashForAccount(account: String): List<ScheduledPost> = listTrash().filter { it.belongsTo(account) }

    /** Reads never write, purge files, or release URI grants. Call maintain on a background thread. */
    fun snapshot(): List<ScheduledPost> = synchronized(lock) {
        com.tjg.twidget.data.TwidgetStore.recoverAccountMigration(appContext)
        activeSnapshot(readAllLocked(), System.currentTimeMillis())
    }

    fun maintain() = transaction { Unit }

    fun moveToTrash(id: String, nowMillis: Long = System.currentTimeMillis()): ScheduledPost? =
        update(id) { it.copy(deletedAt = nowMillis, updatedAt = nowMillis) }
    fun restoreFromTrash(id: String, nowMillis: Long = System.currentTimeMillis()): ScheduledPost? =
        update(id) { if (it.deletedAt == null) it else it.copy(deletedAt = null, updatedAt = nowMillis) }
    fun cancel(id: String, nowMillis: Long = System.currentTimeMillis()): ScheduledPost? =
        update(id) { it.copy(status = ScheduleStatus.CANCELLED, updatedAt = nowMillis, errorMessage = null) }

    fun setPinned(ids: Set<String>, pinned: Boolean, now: Long = System.currentTimeMillis()) = transaction { posts ->
        posts.replaceAll { if (it.id in ids && ScheduleQueuePolicy.canPin(it.status)) it.copy(pinned = pinned, updatedAt = now) else it }
    }
    fun trash(ids: Set<String>, now: Long = System.currentTimeMillis()) = transaction { posts ->
        posts.replaceAll { if (it.id in ids) it.copy(deletedAt = now, updatedAt = now) else it }
    }
    fun restore(ids: Set<String>, now: Long = System.currentTimeMillis()) = transaction { posts ->
        posts.replaceAll { if (it.id in ids) it.copy(deletedAt = null, updatedAt = now) else it }
    }
    fun remove(id: String): Boolean = removeAll(setOf(id)).isNotEmpty()
    fun removeAll(ids: Set<String>): List<ScheduledPost> = transaction { posts ->
        val removed = posts.filter { it.id in ids }
        posts.removeAll { it.id in ids }
        removed
    }
    fun removeForAccount(account: String): List<ScheduledPost> = transaction { posts ->
        val removed = posts.filter { it.belongsTo(account) }
        posts.removeAll(removed.toSet())
        removed
    }

    private fun update(id: String, transform: (ScheduledPost) -> ScheduledPost): ScheduledPost? = transaction { posts ->
        val index = posts.indexOfFirst { it.id == id }
        if (index < 0) null else transform(posts[index]).also { posts[index] = it }
    }

    /** Caller must not do network work inside this edit; notifications run after the successful commit. */
    internal fun <T> transaction(edit: (MutableList<ScheduledPost>) -> T): T = synchronized(lock) {
        com.tjg.twidget.data.TwidgetStore.recoverAccountMigration(appContext)
        val previous = readAllLocked()
        val posts = activeSnapshot(previous, System.currentTimeMillis()).toMutableList()
        val result = edit(posts)
        posts.replaceAll { post ->
            val canonical = com.tjg.twidget.data.TwidgetStore.canonicalAccount(appContext, post.accountUsername)
            if (canonical.equals(post.accountUsername, true)) post else post.copy(
                accountUsername = canonical,
                accountId = if (post.accountId?.equals(post.accountUsername, true) == true) canonical else post.accountId,
            )
        }
        if (posts == previous) return@synchronized result
        writeAllLocked(posts)
        releaseUnusedLocalMedia(previous, posts)
        val retained = posts.mapTo(hashSetOf()) { it.id }
        previous.filterNot { it.id in retained }.forEach { ScheduleChecklistProgress.clear(appContext, it.id) }
        result
    }

    private fun readAllLocked(): List<ScheduledPost> {
        val raw = preferences.getString(KEY_POSTS, null)
        if (!loaded || raw != cachedRaw) {
            cachedPosts = raw?.let { runCatching { ScheduleJsonCodec.decodeList(it) }.getOrNull() }.orEmpty()
            cachedRaw = raw
            loaded = true
        }
        return cachedPosts
    }

    private fun activeSnapshot(posts: List<ScheduledPost>, now: Long): List<ScheduledPost> = posts
        .filter { it.deletedAt == null || it.deletedAt >= now - ScheduleTrashPolicy.RETENTION_MS }
        .map { BufferScheduleFallbackPolicy.reconcile(it, now) }

    private fun writeAllLocked(posts: List<ScheduledPost>) {
        val encoded = ScheduleJsonCodec.encodeList(posts)
        val editor = preferences.edit().putString(KEY_POSTS, encoded)
        // Preserve corrupt storage before any mutation overwrites it; ordinary reads remain pure.
        val raw = cachedRaw
        if (raw != null && !preferences.contains(KEY_CORRUPT_BACKUP) &&
            runCatching { ScheduleJsonCodec.decodeList(raw) }.isFailure) editor.putString(KEY_CORRUPT_BACKUP, raw)
        check(editor.commit()) { "Unable to persist schedules" }
        cachedRaw = encoded
        cachedPosts = posts.toList()
        loaded = true
    }

    private fun ScheduledPost.belongsTo(account: String): Boolean =
        listOf(accountId.orEmpty(), accountUsername).any {
            normalizeLegacyScheduleAccount(it) == normalizeLegacyScheduleAccount(account)
        }

    private fun releaseUnusedLocalMedia(previous: Collection<ScheduledPost>, next: Collection<ScheduledPost>) {
        val retained = next.flatMap { it.localMediaUris() }.toSet()
        previous.flatMap { it.localMediaUris() }.toSet().filterNot(retained::contains).forEach { value ->
            if (!ScheduleOwnedMedia.delete(appContext, value)) runCatching {
                appContext.contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
    private fun ScheduledPost.localMediaUris(): Set<String> =
        thread.flatMap { it.media.filterIsInstance<LocalUriMedia>().map(LocalUriMedia::uri) }.toSet()

    companion object {
        const val PREFS_NAME = "twidget_schedules"
        private const val KEY_POSTS = "posts"
        private const val KEY_CORRUPT_BACKUP = "posts_corrupt_backup"
        private val lock = com.tjg.twidget.data.TwidgetStore
    }
}
