package com.tjg.twidget.social

import androidx.preference.PreferenceGroup
import androidx.preference.ListPreference
import androidx.preference.EditTextPreference
import com.tjg.twidget.R

internal fun PreferenceGroup.addDisplayChoices(catalog: SocialCatalog, profile: SocialProfile,
    nameId: String, avatarId: String, customName: String,
    nameChanged: (String) -> Unit, avatarChanged: (String) -> Unit, customChanged: (String) -> Unit) {
    val members = profile.accountIds.map { catalog.accountsById.getValue(it) }
    listOf(true, false).forEach { name ->
        addPreference(ListPreference(context).apply {
            key = if (name) "social_name_source" else "social_avatar_source"; isPersistent = false
            title = context.getString(if (name) R.string.social_name_source else R.string.social_avatar_source)
            dialogTitle = title
            entries = members.map { "${it.displayName.ifBlank { it.handle }} · ${it.platform.label}" }.toTypedArray()
            entryValues = members.map { it.id }.toTypedArray()
            value = if (name) nameId else avatarId
            summary = entry
            fun preview(id: String) {
                val member = catalog.accountsById[id] ?: return
                if (name) { icon = member.platform.icon(context); return }
                val size = (48 * context.resources.displayMetrics.density).toInt()
                val cached = com.tjg.twidget.ui.ProfileImageLoader.cachedCircularBitmap(context, member.avatarUrl, size)
                if (cached != null) icon = android.graphics.drawable.BitmapDrawable(context.resources, cached)
                else {
                    icon = member.platform.icon(context)
                    val app = context.applicationContext
                    com.tjg.twidget.core.AppExecutors.execute {
                        com.tjg.twidget.ui.ProfileImageLoader.downloadToCache(app, member.avatarUrl)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            com.tjg.twidget.ui.ProfileImageLoader.cachedCircularBitmap(app, member.avatarUrl, size)?.let { bitmap ->
                                if (value == id) icon = android.graphics.drawable.BitmapDrawable(app.resources, bitmap)
                            }
                        }
                    }
                }
            }
            preview(value)
            setOnPreferenceChangeListener { _, selected ->
                val id = selected.toString(); if (name) nameChanged(id) else avatarChanged(id)
                summary = entries[entryValues.indexOf(id)]; preview(id); true
            }
        })
    }
    addPreference(EditTextPreference(context).apply {
        key = "social_custom_name"; isPersistent = false; title = context.getString(R.string.social_custom_name); dialogTitle = title
        text = customName; summary = customName.ifBlank { context.getString(R.string.social_custom_name_summary) }
        setOnBindEditTextListener { it.setSingleLine(); it.filters = arrayOf(android.text.InputFilter.LengthFilter(100)) }
        setOnPreferenceChangeListener { _, value ->
            val name = value.toString().trim(); customChanged(name)
            summary = name.ifBlank { context.getString(R.string.social_custom_name_summary) }; true
        }
    })
}
