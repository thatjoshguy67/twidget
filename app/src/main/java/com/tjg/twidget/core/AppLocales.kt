package com.tjg.twidget.core

import android.content.Context
import android.content.res.Configuration
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

    fun resolve(languageTag: String? = DEFAULT): Locale = when (languageTag?.lowercase(Locale.ROOT)) {
        "de" -> Locale.GERMAN
        "en" -> Locale.ENGLISH
        else -> applicationLocale()
    }

    fun applicationLocale(): Locale {
        val stored = runCatching { AppCompatDelegate.getApplicationLocales() }.getOrNull()
        return stored?.takeUnless { it.isEmpty }?.get(0) ?: Locale.getDefault()
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
        locale: Locale = applicationLocale(),
    ): String {
        val pattern = if (locale.language == "de") germanPattern else englishPattern
        return SimpleDateFormat(pattern, locale).format(Date(timestamp))
    }
}
