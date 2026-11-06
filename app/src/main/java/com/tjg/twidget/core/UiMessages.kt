package com.tjg.twidget.core

import android.content.Context
import com.tjg.twidget.R
import java.util.Base64
import java.util.Locale

/** Versioned, language-independent error payloads survive storage and a later language change. */
object UiMessages {
    enum class Key(val resource: Int, val plural: Boolean = false) {
        ERROR_CSV_INCONSISTENT(R.string.l10n_error_csv_inconsistent),
        ERROR_CSV_GAP(R.string.l10n_error_csv_gap),
        ERROR_CSV_SIZE(R.string.l10n_error_csv_size),
        ERROR_CSV_QUOTES(R.string.l10n_error_csv_quotes),
        ERROR_CSV_OLD(R.string.import_failure_old_export),
        ERROR_CSV_SYNC(R.string.analytics_import_sync_first),
        ERROR_UNKNOWN(R.string.l10n_error_unknown),
        ERROR_EMPTY_THREAD(R.string.l10n_error_empty_thread),
        ERROR_EMPTY_ITEM(R.string.l10n_error_empty_item),
        ERROR_TEXT_LIMIT(R.string.l10n_error_text_limit),
        ERROR_MEDIA_LIMIT(R.string.l10n_error_media_limit),
        ERROR_FUTURE_TIME(R.string.l10n_error_future_time),
        ERROR_SCHEDULE_FAILED(R.string.l10n_error_schedule_failed),
        ERROR_REMINDER_FAILED(R.string.l10n_error_reminder_failed),
        ERROR_READ_MEDIA(R.string.l10n_error_read_media),
        ERROR_MEDIA_LARGE(R.string.l10n_error_media_large),
        ERROR_SAVE_MEDIA(R.string.l10n_error_save_media),
        ERROR_DOWNLOAD_MEDIA(R.string.l10n_error_download_media),
        ERROR_CSV_ANCHOR(R.string.l10n_error_csv_anchor),
        ERROR_CSV_EMPTY(R.string.l10n_error_csv_empty),
        ERROR_CSV_COLUMN(R.string.l10n_error_csv_column),
        ERROR_CSV_ROW(R.string.l10n_error_csv_row),
        ERROR_CSV_YEAR(R.string.l10n_error_csv_year),
        ERROR_CSV_DUPLICATE(R.string.l10n_error_csv_duplicate),
        ERROR_CSV_INVALID(R.string.l10n_error_csv_invalid),
        ERROR_ACCOUNT_CHANGED(R.string.l10n_error_account_changed),
        ERROR_ACCOUNT_IDENTITY(R.string.l10n_error_account_identity),
        ERROR_ACCOUNT_STALE(R.string.l10n_error_account_stale),
        ERROR_ACCOUNT_EXISTS(R.string.l10n_error_account_exists),
        ERROR_ACCOUNT_INVALID(R.string.l10n_error_account_invalid),
        ERROR_ACCOUNT_SAVE(R.string.l10n_error_account_save)
    }
    data class Message(val key: Key, val arguments: List<String>)
    private val token = Regex("~twidget1:([A-Z_]+)((?::[A-Za-z0-9_-]*)*)~")
    fun message(key: Key, vararg arguments: Any): String = "~twidget1:${key.name}" +
        arguments.joinToString("") { ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(it.toString().toByteArray(Charsets.UTF_8)) } + "~"

    fun decode(value: String): List<Message> = token.findAll(value).mapNotNull { match -> runCatching {
        Message(Key.valueOf(match.groupValues[1]), if (match.groupValues[2].isEmpty()) emptyList() else match.groupValues[2].drop(1).split(':').map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) })
    }.getOrNull() }.toList()

    // Only migrate known pre-token app messages. Provider text never becomes a translation key.
    private fun legacy(value: String): List<Message> {
        val fixed = mapOf(
            "At least one thread item is required." to Key.ERROR_EMPTY_THREAD,
            "A thread item must contain text or media." to Key.ERROR_EMPTY_ITEM,
            "The scheduled time must be in the future." to Key.ERROR_FUTURE_TIME,
            "Unable to schedule reminder" to Key.ERROR_REMINDER_FAILED,
            "Scheduling failed" to Key.ERROR_SCHEDULE_FAILED,
        )
        fixed[value]?.let { return listOf(Message(it, emptyList())) }
        Regex("Text must be ([0-9]+) characters or fewer\\.").matchEntire(value)?.let {
            return listOf(Message(Key.ERROR_TEXT_LIMIT, listOf(it.groupValues[1])))
        }
        Regex("An item can contain at most ([0-9]+) media attachments\\.").matchEntire(value)?.let {
            return listOf(Message(Key.ERROR_MEDIA_LIMIT, listOf(it.groupValues[1])))
        }
        return emptyList()
    }

    fun resolve(context: Context, value: String?, fallback: Int = R.string.l10n_error_unknown): String =
        render(value.orEmpty(), AppLocales.formattingLocale(context), context.getString(fallback)) { key, quantity, args ->
            if (key.plural) context.resources.getQuantityString(key.resource, quantity, *args)
            else context.getString(key.resource, *args)
        }

    internal fun render(value: String, locale: Locale, fallback: String, text: (Key, Int, Array<out Any>) -> String): String {
        val messages = decode(value).ifEmpty { legacy(value) }
        // Remote/provider exception text is diagnostic data, not translated UI copy.
        if (messages.isEmpty()) return fallback
        return messages.joinToString("\n") { message ->
            val arguments: Array<out Any> = message.arguments.map { argument ->
                if (message.key == Key.ERROR_CSV_GAP) runCatching { AppLocales.date(java.time.LocalDate.parse(argument), locale) }.getOrDefault(argument)
                else argument.toLongOrNull()?.let { n -> AppLocales.integer(n, locale) } ?: argument
            }.toTypedArray()
            runCatching { text(message.key, (message.arguments.firstOrNull()?.toLongOrNull() ?: 2L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), arguments) }.getOrDefault(fallback)
        }
    }
}
