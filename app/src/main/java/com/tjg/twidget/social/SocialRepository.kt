package com.tjg.twidget.social

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import org.json.JSONArray
import org.json.JSONObject

/** Disk operations belong on the IO executor. No provider token is stored in this database. */
class SocialRepository(context: Context, databaseName: String = DATABASE_NAME) : Closeable {
    private val appContext = context.applicationContext
    private val publishesWidgets = databaseName == DATABASE_NAME
    private val helper = SocialDatabase(context.applicationContext, databaseName)

    fun catalog(): SocialCatalog = transaction { readCatalog(it) }

    fun edit(transform: (SocialCatalog) -> SocialCatalog): SocialCatalog = writeTransaction { db ->
        transform(readCatalog(db)).validate().also { saveCatalog(db, it) }
    }

    fun observations(accountId: String): List<MetricObservation> = helper.readableDatabase.rawQuery(
        "SELECT * FROM observations WHERE account_id = ? ORDER BY observed_at, source", arrayOf(accountId),
    ).use { rows -> buildList {
        while (rows.moveToNext()) add(MetricObservation(accountId,
            SocialMetric.fromStorageId(rows.string("metric")), rows.nullLong("value"), rows.long("observed_at"),
            rows.string("source"), MetricPrecision.valueOf(rows.string("precision")),
            rows.long("estimated") != 0L, rows.long("imported") != 0L, rows.long("shared_import") != 0L))
    } }

    /** Refresh callbacks for removed accounts cannot recreate their identity. */
    fun recordObservations(accountId: String, observations: List<MetricObservation>): Boolean = writeTransaction { db ->
        require(observations.all { it.accountId == accountId })
        if (readCatalog(db).accounts.none { it.id == accountId }) false
        else { observations.forEach { saveObservation(db, it) }; true }
    }

    /** A successful connection and its first observations become visible together. */
    fun connect(snapshot: SocialProfileResult.Success): PlatformAccount = writeTransaction { db ->
        requireNotNull(snapshot.account.remoteId) { "A connection needs a resolved provider identity" }
        require(snapshot.observations.all { it.accountId == snapshot.account.id })
        val catalog = SocialProfilePolicy.add(readCatalog(db), snapshot.account)
        val account = catalog.accounts.first { it.platform == snapshot.account.platform &&
            it.remoteId == snapshot.account.remoteId }
        saveCatalog(db, catalog)
        snapshot.observations.forEach { saveObservation(db, it.copy(accountId = account.id)) }
        if (publishesWidgets) snapshot.youtubeVideos?.let { YouTubeVideoCache.write(appContext, account.id, it) }
        account
    }

    /** Refresh is distinct from connecting: it must not resurrect an account removed during a request. */
    fun applyRefresh(snapshot: SocialProfileResult.Success): Boolean = writeTransaction { db ->
        require(snapshot.observations.all { it.accountId == snapshot.account.id })
        val catalog = readCatalog(db)
        val existing = catalog.accounts.firstOrNull { it.id == snapshot.account.id }
        if (existing == null || existing.platform != snapshot.account.platform || existing.remoteId != snapshot.account.remoteId) false
        else {
            saveCatalog(db, catalog.copy(accounts = catalog.accounts.map { if (it.id == existing.id) snapshot.account else it }))
            snapshot.observations.forEach { saveObservation(db, it) }
            if (publishesWidgets) snapshot.youtubeVideos?.let { YouTubeVideoCache.write(appContext, existing.id, it) }
            true
        }
    }

    fun needsUpgradeIntroduction(): Boolean = transaction { db ->
        metadata(db, "legacy_onboarded") == "true" && metadata(db, "multiplatform_introduction") != "complete"
    }

    fun completeUpgradeIntroduction() = transaction { db -> setMetadata(db, "multiplatform_introduction", "complete") }

    /**
     * During the staged rollout legacy X preferences remain the authority for X data.
     * Reconcile them without resetting new links, display choices, or non-X accounts.
     * All converted rows and the migration marker commit together; legacy data is untouched.
     */
    fun synchronizeLegacyFrom(context: Context): SocialCatalog = synchronized(writeLock) {
        synchronizeLegacy(com.tjg.twidget.data.TwidgetStore.socialMigrationSnapshot(context))
    }

    fun synchronizeLegacy(snapshot: LegacySocialSnapshot): SocialCatalog = writeTransaction { db ->
        val imported = LegacySocialMigration.catalog(snapshot)
        var catalog = readCatalog(db)
        val previousIds = metadata(db, "legacy_account_ids")?.let { encoded ->
            val values = JSONArray(encoded)
            List(values.length()) { values.getString(it) }.toSet()
        }.orEmpty()
        val currentIds = imported.accounts.map { it.id }.toSet()
        (previousIds - currentIds).forEach { catalog = SocialProfilePolicy.remove(catalog, it) }
        imported.accounts.forEach { incoming ->
            val existing = catalog.accounts.firstOrNull { it.id == incoming.id }
            val importedProfile = imported.profiles.first { incoming.id in it.accountIds }
            // The original profile may still belong to its remaining linked members after removal.
            val newProfile = if (catalog.profiles.any { it.id == importedProfile.id })
                SocialProfile.standalone(incoming.id) else importedProfile
            catalog = if (existing == null) catalog.copy(
                accounts = catalog.accounts + incoming,
                profiles = catalog.profiles + newProfile,
                defaultProfileId = catalog.defaultProfileId ?: newProfile.id,
            ) else catalog.copy(accounts = catalog.accounts.map {
                if (it.id == incoming.id) incoming.copy(remoteId = it.remoteId) else it
            })
        }
        val defaultHandle = SocialPlatform.X.normalizeHandle(snapshot.defaultHandle)
        if (metadata(db, "legacy_default_handle") != defaultHandle) {
            val defaultProfile = catalog.profileFor(LegacySocialMigration.accountId(defaultHandle))
            if (defaultProfile != null) catalog = catalog.copy(defaultProfileId = defaultProfile.id)
        }
        // Only translate widgets whose legacy binding changed. New-UI bindings stay intact.
        val previousWidgets = metadata(db, "legacy_widget_handles")?.let(::JSONObject)
        val currentWidgets = JSONObject()
        val bindings = catalog.widgets.associateBy { it.widgetId }.toMutableMap()
        imported.widgets.forEach { binding ->
            val handle = snapshot.widgetHandles.getValue(binding.widgetId)
            val fingerprint = if (handle.isBlank()) "default:$defaultHandle" else SocialPlatform.X.normalizeHandle(handle)
            val key = binding.widgetId.toString()
            currentWidgets.put(key, fingerprint)
            if (previousWidgets == null || !previousWidgets.has(key) || previousWidgets.getString(key) != fingerprint) {
                bindings[binding.widgetId] = binding
            }
        }
        previousWidgets?.keys()?.forEach { key ->
            if (!currentWidgets.has(key)) bindings.remove(key.toInt())
        }
        catalog = catalog.copy(widgets = bindings.values.toList()).validate()
        saveCatalog(db, catalog)
        // Read raw persisted samples only; render-time backfills and onboarding demos are excluded.
        snapshot.accounts.distinctBy { LegacySocialMigration.accountId(it.handle) }.forEach { account ->
            db.delete("observations", "account_id = ? AND source = ?",
                arrayOf(LegacySocialMigration.accountId(account.handle), LegacySocialMigration.SOURCE))
            LegacySocialMigration.observations(account).forEach { saveObservation(db, it) }
        }
        setMetadata(db, "legacy_account_ids", JSONArray(currentIds.toList()).toString())
        setMetadata(db, "legacy_default_handle", defaultHandle)
        setMetadata(db, "legacy_widget_handles", currentWidgets.toString())
        if (metadata(db, "legacy_migration_version") == null) {
            setMetadata(db, "legacy_onboarded", snapshot.onboarded.toString())
        }
        setMetadata(db, "legacy_migration_version", "1")
        catalog
    }

    private fun readCatalog(db: SQLiteDatabase): SocialCatalog {
        val accounts = db.rawQuery("SELECT * FROM accounts ORDER BY rowid", null).use { rows -> buildList {
            while (rows.moveToNext()) add(PlatformAccount(rows.string("id"),
                SocialPlatform.fromStorageId(rows.string("platform")), rows.nullString("remote_id"),
                rows.string("handle"), rows.string("display_name"), rows.string("avatar_url")))
        } }
        val members = db.rawQuery("SELECT * FROM members ORDER BY position", null).use { rows -> buildList {
            while (rows.moveToNext()) add(rows.string("profile_id") to rows.string("account_id"))
        } }.groupBy({ it.first }, { it.second })
        val profiles = db.rawQuery("SELECT * FROM profiles ORDER BY position", null).use { rows -> buildList {
            while (rows.moveToNext()) add(SocialProfile(rows.string("id"), members.getValue(rows.string("id")),
                rows.string("name_source"), rows.string("avatar_source"), rows.nullString("custom_name"),
                rows.long("membership_version")))
        } }
        val widgets = db.rawQuery("SELECT * FROM widget_bindings ORDER BY widget_id", null).use { rows -> buildList {
            while (rows.moveToNext()) add(SocialWidgetBinding(rows.long("widget_id").toInt(),
                rows.nullString("account_id"), rows.long("follows_default") != 0L))
        } }
        return SocialCatalog(accounts, profiles, metadata(db, "default_profile_id"), widgets).validate()
    }

    private fun saveCatalog(db: SQLiteDatabase, catalog: SocialCatalog) {
        catalog.validate()
        val oldIds = db.rawQuery("SELECT id FROM accounts", null).use { rows -> buildList {
            while (rows.moveToNext()) add(rows.getString(0))
        } }
        (oldIds - catalog.accounts.map { it.id }.toSet()).forEach {
            db.delete("accounts", "id = ?", arrayOf(it))
            if (publishesWidgets) YouTubeVideoCache.clear(appContext, it)
        }
        catalog.accounts.forEach { account ->
            val fields = ContentValues().apply {
                put("id", account.id); put("platform", account.platform.storageId); put("remote_id", account.remoteId)
                put("handle", account.handle); put("display_name", account.displayName); put("avatar_url", account.avatarUrl)
            }
            // REPLACE would delete and cascade existing observations and widget references.
            if (db.update("accounts", fields, "id = ?", arrayOf(account.id)) == 0) db.insertOrThrow("accounts", null, fields)
        }
        db.delete("members", null, null)
        db.delete("profiles", null, null)
        catalog.profiles.forEachIndexed { position, profile ->
            db.insertOrThrow("profiles", null, ContentValues().apply {
                put("id", profile.id); put("name_source", profile.nameAccountId); put("avatar_source", profile.avatarAccountId)
                put("custom_name", profile.customDisplayName); put("membership_version", profile.membershipVersion); put("position", position)
            })
            profile.accountIds.forEachIndexed { index, id ->
                db.insertOrThrow("members", null, ContentValues().apply {
                    put("account_id", id); put("profile_id", profile.id); put("position", index)
                })
            }
        }
        db.delete("widget_bindings", null, null)
        catalog.widgets.forEach { binding ->
            db.insertOrThrow("widget_bindings", null, ContentValues().apply {
                put("widget_id", binding.widgetId); put("account_id", binding.accountId); put("follows_default", binding.followsDefault)
            })
        }
        if (catalog.defaultProfileId == null) db.delete("metadata", "key = ?", arrayOf("default_profile_id"))
        else setMetadata(db, "default_profile_id", catalog.defaultProfileId)
    }

    private fun saveObservation(db: SQLiteDatabase, sample: MetricObservation) {
        db.insertWithOnConflict("observations", null, ContentValues().apply {
            put("account_id", sample.accountId); put("metric", sample.metric.storageId); put("observed_at", sample.observedAt)
            put("source", sample.source); if (sample.value == null) putNull("value") else put("value", sample.value)
            put("precision", sample.precision.name); put("estimated", sample.estimated)
            put("imported", sample.imported); put("shared_import", sample.sharedImport)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Unable to store observation" } }
    }

    private fun metadata(db: SQLiteDatabase, key: String): String? = db.rawQuery(
        "SELECT value FROM metadata WHERE key = ?", arrayOf(key),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun setMetadata(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Unable to store migration state" } }
    }

    private fun <T> writeTransaction(block: (SQLiteDatabase) -> T): T = synchronized(writeLock) {
        val result = transaction(block)
        if (publishesWidgets) {
            val current = catalog()
            SocialWidgetCache.publish(appContext, current, current.accounts.flatMap { observations(it.id) })
        }
        result
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    override fun close() = helper.close()

    companion object { const val DATABASE_NAME = "twidget_social.db"; private val writeLock = Any() }
}

private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.nullString(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
private fun Cursor.nullLong(column: String): Long? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
