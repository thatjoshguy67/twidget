package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.R
import com.tjg.twidget.core.AppLocales
import java.text.NumberFormat
import java.util.Locale

/**
 * Resolves the template copy the Brief engine writes into cards and summaries.
 * Production code resolves string resources in the app language; unit tests
 * supply an implementation that reads the default resources without Android.
 */
interface BriefStrings {
    val locale: Locale

    fun text(id: Int, vararg args: Any): String

    fun quantityText(id: Int, quantity: Int, vararg args: Any): String

    val languageTag: String
        get() = locale.toLanguageTag()

    val isEnglish: Boolean
        get() = locale.language.isEmpty() || locale.language == "en"

    fun number(value: Long): String = NumberFormat.getIntegerInstance(locale).format(value)

    fun followers(value: Long): String =
        quantityText(R.plurals.brief_follower_count, quantity(value), number(value))

    companion object {
        fun from(context: Context, languageTag: String = AppLocales.DEFAULT): BriefStrings =
            ResourceBriefStrings(AppLocales.wrap(context, languageTag))

        fun quantity(value: Long): Int = value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}

private class ResourceBriefStrings(private val context: Context) : BriefStrings {
    override val locale: Locale =
        AppLocales.supportedLocale(context.resources.configuration.locales.get(0) ?: Locale.getDefault())

    override fun text(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)

    override fun quantityText(id: Int, quantity: Int, vararg args: Any): String =
        if (args.isEmpty()) {
            context.resources.getQuantityString(id, quantity)
        } else {
            context.resources.getQuantityString(id, quantity, *args)
        }
}
