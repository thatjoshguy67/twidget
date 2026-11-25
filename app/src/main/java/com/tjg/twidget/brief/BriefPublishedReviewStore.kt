package com.tjg.twidget.brief

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.data.TwidgetStore
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Locale

/** Keep the latest successful review per account/tweet until a replacement succeeds. */
internal object BriefPublishedReviewStore {
    private const val DRAFT_LIFETIME = 7 * 24 * 3_600_000L
    private var helper: Database? = null
    private val mutableUpdates = MutableStateFlow(0L)
    val updates = mutableUpdates.asStateFlow()
    private fun database(context: Context): SQLiteDatabase {
        if (helper == null) helper = Database(context.applicationContext)
        return helper!!.writableDatabase
    }
    private fun account(value: String) = value.trim().trimStart('@').lowercase(Locale.US)
    internal fun postId(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host?.lowercase(Locale.US) !in setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com")) null
        else Regex("/(?:[^/]+/status|i/web/status)/(\\d+)/?").matchEntire(uri.path)?.groupValues?.get(1)
    }.getOrNull()

    fun read(context: Context, target: BriefReviewTarget): BriefSavedReview? = synchronized(TwidgetStore) {
        if (target.draftId != null) return@synchronized null
        val id = target.postUrl?.let(::postId) ?: return@synchronized null
        val owner = TwidgetStore.canonicalAccount(context, target.username)
        if (TwidgetStore.accounts(context).none { it.equals(owner, true) }) return@synchronized null
        val raw = database(context).query("reviews", arrayOf("payload"), "account=? AND post_id=?", arrayOf(account(owner), id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return@synchronized null
        val review = runCatching { BriefSavedReviewCodec.decode(JSONObject(raw), owner) }.getOrNull()?.takeIf { postId(it.subject.post!!.url) == id }
        if (review == null) database(context).delete("reviews", "account=? AND post_id=?", arrayOf(account(owner), id))
        review
    }

    fun save(context: Context, subject: BriefReviewSubject, result: BriefCoachGeneration.Success, generation: Long,
        replaceSavedAt: Long? = null, now: Long = System.currentTimeMillis()): BriefSavedReview? = synchronized(TwidgetStore) {
        val post = subject.post ?: return@synchronized null
        require(subject.draft == null)
        val owner = subject.target.username
        val id = postId(post.url) ?: return@synchronized null
        if (postId(subject.target.postUrl.orEmpty()) != id ||
            !TwidgetStore.canonicalAccount(context, post.authorUserName).equals(owner, true)) return@synchronized null
        if (TwidgetStore.accounts(context).none { it.equals(owner, true) } || TwidgetStore.accountGeneration(context, owner) != generation) return@synchronized null
        val existing = read(context, subject.target)
        // A late attempt must not overwrite a newer successful review.
        if (existing != null && existing.savedAt != replaceSavedAt) return@synchronized existing
        val review = BriefSavedReview(subject, result, maxOf(now, (existing?.savedAt ?: 0) + 1))
        val encoded = BriefSavedReviewCodec.encode(review).toString()
        database(context).insertWithOnConflict("reviews", null, ContentValues().apply {
            put("account", account(owner)); put("post_id", id); put("saved_at", review.savedAt)
            put("post", AnalyticsClient.serializePost(post).toString()); put("payload", encoded)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        read(context, subject.target).also { if (it != null) mutableUpdates.value += 1 }
    }

    fun hasReviews(context: Context, username: String, excluding: Set<String> = emptySet()): Boolean = synchronized(TwidgetStore) {
        val owner = TwidgetStore.canonicalAccount(context, username)
        val ids = excluding.mapNotNull(::postId)
        val selection = "account=?" + if (ids.isEmpty()) "" else " AND post_id NOT IN (${ids.joinToString { "?" }})"
        database(context).query("reviews", arrayOf("post_id"), selection, (listOf(account(owner)) + ids).toTypedArray(), null, null, null, "1").use { it.moveToFirst() }
    }

    /** Preserve the original review date and measured snapshot when an automatic check finds no change. */
    fun markChecked(context: Context, saved: BriefSavedReview, generation: Long,
        now: Long = System.currentTimeMillis()): BriefSavedReview? = synchronized(TwidgetStore) {
        val owner = saved.subject.target.username
        if (TwidgetStore.accountGeneration(context, owner) != generation) return@synchronized null
        val current = read(context, saved.subject.target) ?: return@synchronized null
        if (current.savedAt != saved.savedAt) return@synchronized current
        val updated = current.copy(checkedAt = maxOf(now, current.checkedAt))
        database(context).update("reviews", ContentValues().apply {
            put("payload", BriefSavedReviewCodec.encode(updated).toString())
        }, "account=? AND post_id=?", arrayOf(account(owner), postId(saved.subject.post!!.url)))
        updated
    }

    fun posts(context: Context, username: String, limit: Int): List<PostSummary> = synchronized(TwidgetStore) {
        val owner = TwidgetStore.canonicalAccount(context, username)
        database(context).query("reviews", arrayOf("post"), "account=?", arrayOf(account(owner)), null, null, "CAST(post_id AS INTEGER) DESC", limit.coerceAtLeast(1).toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) runCatching {
                NetworkResponseParsers.parseBridgePost(JSONObject(cursor.getString(0)))
            }.getOrNull()?.let(::add) }
        }
    }

    fun readDraft(context: Context, subject: BriefReviewSubject, fingerprint: String,
        now: Long = System.currentTimeMillis()): BriefCachedDraftReview? = synchronized(TwidgetStore) {
        val draft = subject.draft ?: return@synchronized null
        val owner = TwidgetStore.canonicalAccount(context, subject.target.username)
        if (TwidgetStore.accounts(context).none { it.equals(owner, true) }) return@synchronized null
        val args = arrayOf(account(owner), draft.id)
        val entry = database(context).query("draft_reviews", arrayOf("fingerprint", "saved_at", "payload"),
            "account=? AND draft_id=?", args, null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else Triple(cursor.getString(0), cursor.getLong(1), cursor.getString(2))
        } ?: return@synchronized null
        if (entry.first != fingerprint || now < entry.second || now - entry.second >= DRAFT_LIFETIME) {
            database(context).delete("draft_reviews", "account=? AND draft_id=?", args)
            return@synchronized null
        }
        val result = runCatching { BriefCoachResultCodec.decode(JSONObject(entry.third), subject.input) }.getOrNull()
        if (result == null) database(context).delete("draft_reviews", "account=? AND draft_id=?", args)
        result?.let { BriefCachedDraftReview(it, entry.second) }
    }

    fun saveDraft(context: Context, subject: BriefReviewSubject, fingerprint: String,
        result: BriefCoachGeneration.Success, generation: Long, now: Long = System.currentTimeMillis()): BriefCachedDraftReview? = synchronized(TwidgetStore) {
        val draft = subject.draft ?: return@synchronized null
        val owner = subject.target.username
        if (subject.target.draftId != draft.id || !TwidgetStore.canonicalAccount(context, BriefPostCoach.account(draft)).equals(owner, true) ||
            TwidgetStore.accounts(context).none { it.equals(owner, true) } || TwidgetStore.accountGeneration(context, owner) != generation)
            return@synchronized null
        database(context).insertWithOnConflict("draft_reviews", null, ContentValues().apply {
            put("account", account(owner)); put("draft_id", draft.id); put("fingerprint", fingerprint)
            put("saved_at", now); put("payload", BriefCoachResultCodec.encode(result).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // Keep only recent advice for at most 32 drafts per account. Never store images or keys.
        database(context).delete("draft_reviews", "account=? AND (saved_at<=? OR draft_id NOT IN " +
            "(SELECT draft_id FROM draft_reviews WHERE account=? ORDER BY saved_at DESC LIMIT 32))",
            arrayOf(account(owner), (now - DRAFT_LIFETIME).toString(), account(owner)))
        BriefCachedDraftReview(result, now)
    }

    /** Account migration copies first; the old rows are removed only after account selection commits. */
    fun copyAccount(context: Context, old: String, next: String) = synchronized(TwidgetStore) {
        database(context).execSQL("INSERT OR IGNORE INTO reviews(account,post_id,saved_at,post,payload) SELECT ?,post_id,saved_at,post,payload FROM reviews WHERE account=?",
            arrayOf(account(next), account(old)))
    }
    fun removeAccount(context: Context, username: String) = synchronized(TwidgetStore) {
        database(context).delete("reviews", "account=?", arrayOf(account(username)))
        database(context).delete("draft_reviews", "account=?", arrayOf(account(username)))
    }
    internal fun closeForTest() = synchronized(TwidgetStore) { helper?.close(); helper = null }

    private class Database(context: Context) : SQLiteOpenHelper(context, "twidget_tweet_reviews.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE reviews(account TEXT NOT NULL, post_id TEXT NOT NULL, saved_at INTEGER NOT NULL, post TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(account,post_id))")
            createDrafts(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createDrafts(db) }
        private fun createDrafts(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE draft_reviews(account TEXT NOT NULL, draft_id TEXT NOT NULL, fingerprint TEXT NOT NULL, saved_at INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(account,draft_id))")
        }
    }
}
