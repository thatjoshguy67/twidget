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
 * Widget settings may override the app language with an explicit language tag;
 * `DEFAULT` follows [AppCompatDelegate] when a language is stored, else the
 * system locale.
 */
object AppLocales {
    const val DEFAULT = "DEFAULT"

    private var appContext: Context? = null
    private val catalanThousandsSuffix = Regex("[\\s\\u00a0\\u202f]*mil$")

    /** Restore AppCompat's persisted choice before a worker or widget is started. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AppCompatDelegate.setApplicationLocales(LocaleManagerCompat.getApplicationLocales(context))
        }
    }

    private val supportedLanguages = setOf("ca", "de", "en", "es", "fr", "nl")

    internal fun supportedLocale(locale: Locale): Locale =
        if (locale.language in supportedLanguages) locale
        else Locale.Builder().setLocale(locale).setLanguage("en").setScript("").build()

    /** A language-only selection inherits the device region; explicit country tags win. */
    internal fun regionalLocale(language: Locale, device: Locale): Locale {
        if (language.country.isNotEmpty() || device.country.isEmpty()) return language
        return Locale.Builder().setLocale(language).setRegion(device.country).apply {
            for (key in listOf("nu", "rg", "hc", "fw")) {
                if (language.getUnicodeLocaleType(key) == null) device.getUnicodeLocaleType(key)?.let {
                    setUnicodeLocaleKeyword(key, it)
                }
            }
        }.build()
    }

    private fun deviceLocale(): Locale =
        if (appContext == null) Locale.getDefault(Locale.Category.FORMAT)
        else Resources.getSystem().configuration.locales[0]

    fun formattingLocale(context: Context): Locale =
        regionalLocale(supportedLocale(context.resources.configuration.locales[0]), deviceLocale())

    fun resolve(languageTag: String? = DEFAULT): Locale {
        if (languageTag.isNullOrBlank() || languageTag.equals(DEFAULT, true)) return applicationLocale()
        val locale = Locale.forLanguageTag(languageTag)
        return if (locale.language in supportedLanguages) regionalLocale(locale, deviceLocale())
        else applicationLocale()
    }

    fun applicationLocale(): Locale {
        val device = deviceLocale()
        if (appContext == null) return supportedLocale(device)
        val stored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManagerCompat.getApplicationLocales(requireNotNull(appContext))
        } else AppCompatDelegate.getApplicationLocales()
        return regionalLocale(supportedLocale(stored.takeUnless { it.isEmpty }?.get(0) ?: device), device)
    }

    fun wrap(context: Context, languageTag: String? = DEFAULT): Context {
        val locale = resolve(languageTag)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    fun integer(value: Long, locale: Locale = applicationLocale()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun date(value: java.time.LocalDate, locale: Locale = applicationLocale()): String =
        value.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))

    /** CLDR supplies compact units, separators, spacing, magnitude and rounding for the region. */
    fun compact(value: Long, locale: Locale = applicationLocale()): String =
        if (value in -999L..999L) integer(value, locale) else android.icu.text.CompactDecimalFormat.getInstance(locale,
            android.icu.text.CompactDecimalFormat.CompactStyle.SHORT).apply {
                maximumSignificantDigits = 3
            }.format(value).let { compactLabel(it, locale) }

    /** Keep Catalan compact counts short while retaining the regional digits and decimal separator. */
    internal fun compactLabel(formatted: String, locale: Locale): String =
        if (locale.language == "ca") formatted.replace(catalanThousandsSuffix, "k") else formatted

    fun percent(value: Double, digits: Int = 1, locale: Locale = applicationLocale()): String =
        NumberFormat.getPercentInstance(locale).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }.format(value)

    fun firstDayOfWeek(locale: Locale = applicationLocale()): java.time.DayOfWeek =
        java.time.temporal.WeekFields.of(locale).firstDayOfWeek

    fun dateTime(context: Context, timestamp: Long): String =
        formatDate(timestamp, "yMMMdjm", formattingLocale(context), context)

    /** Skeletons describe fields, leaving their order, punctuation and clock to the user's locale. */
    fun formatDate(timestamp: Long, skeleton: String = "MMMd", locale: Locale = applicationLocale(),
        context: Context? = appContext): String {
        val date = Date(timestamp)
        // The JVM-only engine also uses this helper without an Android application context.
        if (context == null) {
            val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
            return if (skeleton.any { it in "jHh" }) dateFormat.format(date) + " · " +
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(date)
            else dateFormat.format(date)
        }
        val regionalContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        })
        val clock = if (android.text.format.DateFormat.is24HourFormat(regionalContext)) "H" else "h"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton.replace("j", clock))
        return SimpleDateFormat(pattern, locale).format(date)
    }
}
