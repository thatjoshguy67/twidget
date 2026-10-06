package com.tjg.twidget.core

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.core.app.LocaleManagerCompat
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves the in-app language for UI, widgets, and number/date formatting.
 * Widget settings may override the app locale with an explicit `de` / `en` tag;
 * `DEFAULT` follows [AppCompatDelegate] when a language is stored, else the
 * system locale.
 */
object AppLocales {
    const val DEFAULT = "DEFAULT"

    private var appContext: Context? = null

    /** Restore AppCompat's persisted choice before a worker or widget is started. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AppCompatDelegate.setApplicationLocales(LocaleManagerCompat.getApplicationLocales(context))
        }
    }

    internal fun supportedLocale(locale: Locale): Locale = when (locale.language) {
        "de", "en", "fr" -> locale
        else -> Locale.ENGLISH
    }

    fun resolve(languageTag: String? = DEFAULT): Locale = when (languageTag?.lowercase(Locale.ROOT)) {
        "de" -> Locale.GERMAN
        "en" -> Locale.ENGLISH
        "fr" -> Locale.FRENCH
        else -> applicationLocale()
    }

    fun applicationLocale(): Locale {
        if (appContext == null) return supportedLocale(Locale.getDefault())
        val stored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && appContext != null) {
            LocaleManagerCompat.getApplicationLocales(requireNotNull(appContext))
        } else {
            AppCompatDelegate.getApplicationLocales()
        }
        return supportedLocale(stored.takeUnless { it.isEmpty }?.get(0)
            ?: Resources.getSystem().configuration.locales.get(0) ?: Locale.ENGLISH)
    }

    fun wrap(context: Context, languageTag: String? = DEFAULT): Context {
        val locale = resolve(languageTag)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    fun integer(value: Long, locale: Locale = applicationLocale()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun formatDate(
        timestamp: Long,
        germanPattern: String,
        englishPattern: String,
        frenchPattern: String,
        locale: Locale = applicationLocale(),
    ): String {
        val pattern = when (locale.language) {
            "de" -> germanPattern
            "fr" -> frenchPattern
            else -> englishPattern
        }
        return SimpleDateFormat(pattern, locale).format(Date(timestamp))
    }
}
