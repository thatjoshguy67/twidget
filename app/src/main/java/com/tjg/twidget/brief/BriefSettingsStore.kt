package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.data.SecureCredentialStore

object BriefSettingsStore {
    private const val PREFS = "twidget_brief_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_CONTENT_PREFIX = "content_"
    private const val KEY_CONTENT_REGENERATION_PENDING = "content_regeneration_pending"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun onboardingComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETE, complete).apply()
    }

    fun provider(context: Context): BriefProviderMode =
        BriefProviderMode.fromStorageId(prefs(context).getString(KEY_PROVIDER, null))

    fun setProvider(context: Context, provider: BriefProviderMode) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.storageId).apply()
    }

    fun contentEnabled(context: Context, category: BriefContentCategory): Boolean =
        prefs(context).getBoolean(KEY_CONTENT_PREFIX + category.storageId, true)

    fun setContentEnabled(
        context: Context,
        category: BriefContentCategory,
        enabled: Boolean,
    ) {
        if (contentEnabled(context, category) == enabled) return
        prefs(context).edit()
            .putBoolean(KEY_CONTENT_PREFIX + category.storageId, enabled)
            .putBoolean(KEY_CONTENT_REGENERATION_PENDING, true)
            .apply()
    }

    fun contentRegenerationPending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONTENT_REGENERATION_PENDING, false)

    fun clearContentRegenerationPending(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONTENT_REGENERATION_PENDING, false).apply()
    }

    fun enabledContent(context: Context): Set<BriefContentCategory> =
        BriefContentCategory.entries.filterTo(linkedSetOf()) { contentEnabled(context, it) }

    fun contentFingerprint(context: Context): String = BriefContentCategory.entries.joinToString(",") {
        "${it.storageId}:${contentEnabled(context, it)}"
    }

    fun cloudApiKey(context: Context): String =
        SecureCredentialStore.read(context, SecureCredentialStore.GEMINI_API_KEY)

    fun setCloudApiKey(context: Context, apiKey: String) {
        SecureCredentialStore.write(context, mapOf(SecureCredentialStore.GEMINI_API_KEY to apiKey))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
