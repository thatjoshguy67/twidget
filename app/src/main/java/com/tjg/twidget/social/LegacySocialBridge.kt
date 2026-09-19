package com.tjg.twidget.social

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore

/** Coalesces legacy X writes while the UI is being migrated to platform repositories. */
object LegacySocialBridge {
    private val lock = Any()
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var dirty = false
    private var running = false

    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (listener != null) return
            listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in setOf("accounts", "username", "onboarded", "profile", "history") ||
                    key.startsWith("profile_") || key.startsWith("history_") || key.startsWith("widget_account")) {
                    requestSync(app)
                }
            }.also { app.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(it) }
        }
        requestSync(app)
    }

    private fun requestSync(context: Context) {
        synchronized(lock) {
            dirty = true
            if (running) return
            running = true
        }
        AppExecutors.execute(onRejected = { synchronized(lock) { running = false } }) {
            while (true) {
                synchronized(lock) { dirty = false }
                runCatching {
                    val snapshot = TwidgetStore.socialMigrationSnapshot(context)
                    SocialRepository(context).use { it.synchronizeLegacy(snapshot) }
                }.onFailure {
                    // Keep the old store usable; retry on the next account write or app launch.
                    // Do not log account data, preference payloads or exception messages.
                    Log.w("SocialMigration", "Account migration deferred; legacy data retained")
                }
                synchronized(lock) {
                    if (!dirty) { running = false; return@execute }
                }
            }
        }
    }
}
