package com.tjg.twidget.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefApiKeyDialog
import com.tjg.twidget.brief.BriefProviderMode
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.ui.startRightSidePopOverActivity
import dev.oneuiproject.oneui.preference.SwitchBarPreference

class BriefSettingsPreferenceFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val account = TwidgetStore.settings(context).username
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(SwitchBarPreference(context).apply {
            key = "brief_enabled_pref"
            isChecked = BriefSettingsStore.enabled(context)
            setOnPreferenceChangeListener { _, value ->
                BriefSettingsStore.setEnabled(context, value as Boolean)
                true
            }
        })

        screen.addPreference(spacerCategory())
        val preview = androidx.appcompat.widget.AppCompatImageView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (220 * resources.displayMetrics.density).toInt(),
            )
            setImageResource(R.drawable.brief_settings_preview)
            setBackgroundResource(R.drawable.brief_settings_preview_background)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            contentDescription = getString(R.string.brief_settings_preview_description)
        }
        screen.addPreference(dev.oneuiproject.oneui.preference.LayoutPreference(context, preview).apply {
            key = "brief_preview"
            isSelectable = false
            setAllowDividerAbove(false)
            setAllowDividerBelow(false)
        })
        val intro = layoutInflater.inflate(R.layout.preference_brief_intro, null)
        screen.addPreference(dev.oneuiproject.oneui.preference.LayoutPreference(context, intro).apply {
            key = "brief_intro"
            isSelectable = false
            setAllowDividerAbove(false)
            setAllowDividerBelow(false)
        })

        screen.addPreference(spacerCategory())
        screen.addPreference(Preference(context).apply {
            key = "brief_content_pref"
            title = getString(R.string.brief_manage_content)
            setOnPreferenceClickListener {
                requireActivity().startRightSidePopOverActivity(
                    Intent(context, BriefContentSettingsActivity::class.java),
                )
                true
            }
        })

        screen.addPreference(spacerCategory())
        val provider = BriefSettingsStore.provider(context)
        screen.addPreference(ListPreference(context).apply {
            key = "brief_provider_pref"
            title = getString(R.string.brief_provider)
            entries = arrayOf(
                getString(R.string.brief_provider_auto),
                getString(R.string.brief_provider_local),
                getString(R.string.brief_provider_cloud),
            )
            entryValues = BriefProviderMode.entries.map { it.storageId }.toTypedArray()
            value = provider.storageId
            summary = providerLabel(provider)
            setOnPreferenceChangeListener { preference, newValue ->
                val selected = BriefProviderMode.fromStorageId(newValue as String)
                BriefSettingsStore.setProvider(context, selected)
                BriefStore.resetAi(context, account)
                preference.summary = providerLabel(selected)
                listView.post { buildScreen() }
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_cloud_api_key_pref"
            title = getString(R.string.brief_ai_studio_key)
            isVisible = provider != BriefProviderMode.LOCAL
            val current = BriefSettingsStore.cloudApiKey(context)
            summary = if (current.isBlank()) null else getString(R.string.brief_cloud_key_configured)
            setOnPreferenceClickListener {
                BriefApiKeyDialog.show(requireActivity(), required = false) { key ->
                    BriefStore.resetAi(context, account)
                    summary = if (key.isBlank()) null else {
                        getString(R.string.brief_cloud_key_configured)
                    }
                    listView.post { buildScreen() }
                }
                true
            }
        })

        if (TwidgetStore.debugMenuUnlocked(context)) {
            screen.addPreference(spacerCategory())
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_pref"
                title = getString(R.string.brief_settings_debug)
                setOnPreferenceClickListener {
                    requireActivity().startSettingsSubActivity(
                        Intent(context, BriefDebugActivity::class.java),
                    )
                    true
                }
            })
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun providerLabel(provider: BriefProviderMode): String = getString(
        when (provider) {
            BriefProviderMode.AUTO -> R.string.brief_provider_auto_short
            BriefProviderMode.LOCAL -> R.string.brief_provider_local
            BriefProviderMode.CLOUD -> R.string.brief_provider_cloud
        },
    )

    private fun spacerCategory() = PreferenceCategory(requireContext()).apply {
        isIconSpaceReserved = false
    }

}
