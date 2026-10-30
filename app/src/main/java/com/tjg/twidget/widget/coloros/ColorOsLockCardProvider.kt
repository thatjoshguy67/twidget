package com.tjg.twidget.widget.coloros

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.widget.WidgetKind
import org.json.JSONObject

/** Experimental Instant/lite producer. Catalogue enrollment is deliberately separate. */
class ColorOsLockCardProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        enforceHost(ctx)
        val path = extras?.getString("path")
        ColorOsCardProtocol.error(method, extras?.getString("package"), path, extras?.getString("instanceId"))
            ?.let { return response(-1, it) }
        // No network, worker scheduling or background activity launch in Binder callbacks.
        // Defaults intentionally match the existing One UI lock widgets in this first version.
        return when (method) {
            "onCardCreate", "onCardUpdate", "onCardSubscribe" -> response(0, "success").apply {
                putString("data", presentation(ctx, path == WIDE).toString())
            }
            "onCardEvent" -> response(-1, "Use the template's configuration route")
            else -> response(0, "success")
        }
    }

    private fun enforceHost(ctx: Context) {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val pm = ctx.packageManager
        val allowed = pm.getPackagesForUid(uid).orEmpty().any { pkg ->
            pkg in HOSTS && pm.checkSignatures(pkg, "android") == PackageManager.SIGNATURE_MATCH
        }
        if (!allowed) throw SecurityException("Untrusted native card caller")
    }

    // This producer exposes only the narrow call protocol; no general data/file access.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val RPK_PACKAGE = "com.tjg.twidget.coloroscards"
        const val COMPACT = "/cards/compact"
        const val WIDE = "/cards/wide"
        private val HOSTS = setOf("com.android.systemui", "com.oplus.keyguard.style.widgets", "com.nearme.instant.card", "com.nearme.instant.platform")

        internal fun presentation(context: Context, wide: Boolean): JSONObject {
            val settings = TwidgetStore.widgetSettings(context, kind = WidgetKind.LOCK_SCREEN)
            val account = settings.accountUsername.ifBlank { TwidgetStore.settings(context).username }
            val stats = TwidgetStore.currentStats(context, account)
            val delta = TwidgetStore.followersDelta(context, account)
            val locale = AppLocales.resolve(settings.language)
            val followers = AppLocales.wrap(context, settings.language).getString(R.string.followers)
            val label = if (locale.language == "de") followers else followers.lowercase(locale)
            val count = if (account.isBlank()) "—" else AppLocales.integer(stats.followersCount, locale)
            val detail = if (!settings.showDelta || account.isBlank()) "" else if (delta == 0L) label else TwidgetStore.signedNumber(delta)
            return JSONObject().apply {
                put("schemaVersion", 1)
                put("count", count)
                put("delta", detail)
                put("deltaSign", delta.compareTo(0L))
                put("logo", settings.logo)
                put("logoText", if (settings.logo == TwidgetStore.LOGO_TWITTER) "Twitter" else "𝕏")
                put("wide", wide)
                put("description", "$count $label $detail".trim())
                // AOD and host colours are template/host state, never inferred from screen power here.
            }
        }

        fun notifyChanged(context: Context) {
            val root = Uri.parse("content://${context.packageName}.coloros.lockcards/$RPK_PACKAGE")
            // Notify the ancestor with descendant delivery: avoids persisting ephemeral host sessions.
            ColorOsCardProtocol.paths.forEach { path ->
                context.contentResolver.notifyChange(Uri.parse(root.toString() + path), null)
            }
        }

        private fun response(code: Int, message: String) = Bundle().apply {
            putInt("code", code)
            putString("message", message)
        }
    }
}
