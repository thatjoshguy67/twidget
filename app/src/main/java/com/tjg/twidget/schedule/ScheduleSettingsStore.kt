package com.tjg.twidget.schedule

import android.content.Context
import com.tjg.twidget.R
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
        bufferChannelId: String?,
    ): Map<String, String> = mappings.toMutableMap().apply {
        val key = normalize(trackedUsername)
        val value = bufferChannelId?.trim().orEmpty()
        if (value.isBlank()) remove(key) else put(key, value)
    }
}

object ScheduleSettingsStore {
    private const val KEY_DEFAULT_PROVIDER = "schedule_default_provider"
    private const val KEY_BUFFER_MAPPINGS = "schedule_buffer_mappings"
    private const val KEY_BUFFER_CHANNEL_NAMES = "schedule_buffer_channel_names"
    private const val KEY_LEGACY_POSTPONE_MAPPINGS = "schedule_postpone_mappings"
    private const val KEY_CLOUDINARY_CLOUD_NAME = "schedule_cloudinary_cloud_name"
    private const val KEY_CLOUDINARY_UPLOAD_PRESET = "schedule_cloudinary_upload_preset"

    fun defaultProvider(context: Context): ScheduleProvider {
        val stored = prefs(context).getString(KEY_DEFAULT_PROVIDER, null)
        if (stored == "POSTPONE") {
            SecureCredentialStore.clear(context, SecureCredentialStore.LEGACY_POSTPONE_API_KEY)
            prefs(context).edit()
                .remove(KEY_LEGACY_POSTPONE_MAPPINGS)
                .putString(KEY_DEFAULT_PROVIDER, ScheduleProvider.LOCAL_REMINDER.name)
                .apply()
            return ScheduleProvider.LOCAL_REMINDER
        }
        return runCatching { ScheduleProvider.valueOf(stored.orEmpty()) }
            .getOrDefault(ScheduleProvider.LOCAL_REMINDER)
    }

    fun setDefaultProvider(context: Context, provider: ScheduleProvider) {
        prefs(context).edit().putString(KEY_DEFAULT_PROVIDER, provider.name).apply()
    }

    fun bufferChannelFor(context: Context, trackedUsername: String): String? =
        ScheduleAccountMapping.resolve(mappings(context), trackedUsername)

    fun mappings(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_BUFFER_MAPPINGS, null) ?: return emptyMap()
        return runCatching {
            val objectValue = JSONObject(raw)
            buildMap {
                objectValue.keys().forEach { key ->
                    objectValue.optString(key).takeIf(String::isNotBlank)?.let {
                        put(ScheduleAccountMapping.normalize(key), it.trim())
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun setBufferChannel(context: Context, trackedUsername: String, bufferChannelId: String?) {
        val next = ScheduleAccountMapping.updated(mappings(context), trackedUsername, bufferChannelId)
        val encoded = JSONObject().apply {
            next.forEach { (tracked, mapped) -> put(tracked, mapped) }
        }.toString()
        prefs(context).edit().putString(KEY_BUFFER_MAPPINGS, encoded).apply()
        if (bufferChannelId.isNullOrBlank()) {
            setBufferChannelName(context, trackedUsername, null)
        }
    }

    fun setBufferChannel(context: Context, trackedUsername: String, channel: BufferChannel?) {
        setBufferChannel(context, trackedUsername, channel?.id)
        setBufferChannelName(context, trackedUsername, channel?.name)
    }

    fun rememberBufferChannel(context: Context, trackedUsername: String, channel: BufferChannel) {
        if (bufferChannelFor(context, trackedUsername) == channel.id) {
            setBufferChannelName(context, trackedUsername, channel.name)
        }
    }

    fun bufferChannelUsernameFor(context: Context, trackedUsername: String): String? =
        stringMap(context, KEY_BUFFER_CHANNEL_NAMES)[ScheduleAccountMapping.normalize(trackedUsername)]

    private fun setBufferChannelName(context: Context, trackedUsername: String, channelName: String?) {
        val key = ScheduleAccountMapping.normalize(trackedUsername)
        val next = stringMap(context, KEY_BUFFER_CHANNEL_NAMES).toMutableMap().apply {
            val value = channelName?.trim()?.trimStart('@').orEmpty()
            if (value.isBlank()) remove(key) else put(key, value)
        }
        val encoded = JSONObject().apply { next.forEach { (tracked, name) -> put(tracked, name) } }.toString()
        prefs(context).edit().putString(KEY_BUFFER_CHANNEL_NAMES, encoded).apply()
    }

    private fun stringMap(context: Context, preferenceKey: String): Map<String, String> {
        val raw = prefs(context).getString(preferenceKey, null) ?: return emptyMap()
        return runCatching {
            val objectValue = JSONObject(raw)
            buildMap {
                objectValue.keys().forEach { key ->
                    objectValue.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) }
                }
            }
        }.getOrDefault(emptyMap())
    }

    // The Cloudinary account ships with the build (resValue) so users get
    // media hosting without any setup; the preference is a per-user override.
    fun cloudinaryCloudName(context: Context): String? =
        cloudinaryCloudNameOverride(context) ?: builtInString(context, R.string.cloudinary_cloud_name)

    fun cloudinaryCloudNameOverride(context: Context): String? =
        prefs(context).getString(KEY_CLOUDINARY_CLOUD_NAME, null)?.trim()?.takeIf(String::isNotBlank)

    fun setCloudinaryCloudName(context: Context, value: String?) {
        setTrimmedOrRemove(context, KEY_CLOUDINARY_CLOUD_NAME, value)
    }

    fun cloudinaryUploadPreset(context: Context): String? =
        cloudinaryUploadPresetOverride(context) ?: builtInString(context, R.string.cloudinary_upload_preset)

    fun cloudinaryUploadPresetOverride(context: Context): String? =
        prefs(context).getString(KEY_CLOUDINARY_UPLOAD_PRESET, null)?.trim()?.takeIf(String::isNotBlank)

    fun setCloudinaryUploadPreset(context: Context, value: String?) {
        setTrimmedOrRemove(context, KEY_CLOUDINARY_UPLOAD_PRESET, value)
    }

    private fun builtInString(context: Context, resId: Int): String? =
        context.getString(resId).trim().takeIf(String::isNotBlank)

    private fun setTrimmedOrRemove(context: Context, preferenceKey: String, value: String?) {
        val trimmed = value?.trim().orEmpty()
        prefs(context).edit().apply {
            if (trimmed.isBlank()) remove(preferenceKey) else putString(preferenceKey, trimmed)
        }.apply()
    }

    fun clearBuffer(context: Context) {
        BufferOAuth.disconnect(context)
        ScheduleStore(context).list().filter { it.provider == ScheduleProvider.BUFFER }.forEach {
            BufferPublishCheckWorker.cancel(context, it.id)
        }
        prefs(context).edit()
            .remove(KEY_BUFFER_MAPPINGS)
            .remove(KEY_BUFFER_CHANNEL_NAMES)
            .putString(KEY_DEFAULT_PROVIDER, ScheduleProvider.LOCAL_REMINDER.name)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)

}
