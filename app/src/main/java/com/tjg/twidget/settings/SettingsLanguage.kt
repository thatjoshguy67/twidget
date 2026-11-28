package com.tjg.twidget.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.tjg.twidget.R
import com.tjg.twidget.widget.TwidgetBriefWidget
import com.tjg.twidget.widget.TwidgetWidget

internal object SettingsLanguage {
    fun open(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                Uri.fromParts("package", activity.packageName, null)))
        } else {
            showLegacyDialog(activity)
        }
    }

    fun refreshWidgetsIfLanguageChanged(context: android.content.Context) {
        val prefs = context.getSharedPreferences(com.tjg.twidget.data.TwidgetStore.PREFS, android.content.Context.MODE_PRIVATE)
        val language = com.tjg.twidget.core.AppLocales.formattingLocale(context).toLanguageTag()
        val previous = prefs.getString("settings_widget_locale", null)
        if (language != previous) {
            prefs.edit().putString("settings_widget_locale", language).apply()
            if (previous != null) {
                TwidgetWidget.updateAll(context)
                TwidgetBriefWidget.updateAll(context)
            }
        }
    }

    /** Android 12 and earlier have no system page for per-app languages. */
    fun showLegacyDialog(activity: Activity): AlertDialog {
        val tags = arrayOf("", "ca", "de", "en", "es", "fr", "nl", "vi")
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
        val selected = tags.indexOfFirst { if (it.isEmpty()) current.isEmpty() else current.startsWith(it) }.coerceAtLeast(0)
        val labels = arrayOf(activity.getString(R.string.language_system), activity.getString(R.string.language_catalan),
            activity.getString(R.string.language_german), activity.getString(R.string.language_english),
            activity.getString(R.string.language_spanish), activity.getString(R.string.language_french),
            activity.getString(R.string.language_dutch), activity.getString(R.string.language_vietnamese))
        return AlertDialog.Builder(activity)
            .setTitle(R.string.language)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[which]))
                TwidgetWidget.updateAll(activity)
                TwidgetBriefWidget.updateAll(activity)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
