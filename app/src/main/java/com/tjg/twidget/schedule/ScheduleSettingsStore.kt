package com.tjg.twidget.schedule

import android.content.Context
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.data.TwidgetStore
import java.util.Locale
import org.json.JSONObject

internal object ScheduleAccountMapping {
    fun normalize(value: String): String =
        value.trim().trimStart('@').lowercase(Locale.US)

    fun resolve(mappings: Map<String, String>, trackedUsername: String): String? =
        mappings[normalize(trackedUsername)]?.takeIf(String::isNotBlank)

    fun updated(
        mappings: Map<String, String>,
        trackedUsername: String,
        postponeUsername: String?,
    ): Map<String, String> = mappings.toMutableMap().apply {
        val key = normalize(trackedUsername)
        val value = postponeUsername?.let(::normalize).orEmpty()
        if (value.isBlank()) remove(key) else put(key, value)
    }
}

object ScheduleSettingsStore {
    private const val KEY_DEFAULT_PROVIDER = "schedule_default_provider"
    private const val KEY_POSTPONE_MAPPINGS = "schedule_postpone_mappings"

    fun defaultProvider(context: Context): ScheduleProvider {
        val stored = prefs(context).getString(KEY_DEFAULT_PROVIDER, null)
        return runCatching { ScheduleProvider.valueOf(stored.orEmpty()) }
            .getOrDefault(ScheduleProvider.LOCAL_REMINDER)
    }

    fun setDefaultProvider(context: Context, provider: ScheduleProvider) {
        prefs(context).edit().putString(KEY_DEFAULT_PROVIDER, provider.name).apply()
    }

    fun postponeAccountFor(context: Context, trackedUsername: String): String? =
        ScheduleAccountMapping.resolve(mappings(context), trackedUsername)

    fun mappings(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_POSTPONE_MAPPINGS, null) ?: return emptyMap()
        return runCatching {
            val objectValue = JSONObject(raw)
            buildMap {
                objectValue.keys().forEach { key ->
                    objectValue.optString(key).takeIf(String::isNotBlank)?.let {
                        put(ScheduleAccountMapping.normalize(key), ScheduleAccountMapping.normalize(it))
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setPostponeAccount(context: Context, trackedUsername: String, postponeUsername: String?) {
        val next = ScheduleAccountMapping.updated(mappings(context), trackedUsername, postponeUsername)
        val encoded = JSONObject().apply {
            next.forEach { (tracked, mapped) -> put(tracked, mapped) }
        }.toString()
        prefs(context).edit().putString(KEY_POSTPONE_MAPPINGS, encoded).apply()
    }

    fun clearPostpone(context: Context) {
        SecureCredentialStore.clear(context, SecureCredentialStore.POSTPONE_API_KEY)
        prefs(context).edit()
            .remove(KEY_POSTPONE_MAPPINGS)
            .putString(KEY_DEFAULT_PROVIDER, ScheduleProvider.LOCAL_REMINDER.name)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)

}
