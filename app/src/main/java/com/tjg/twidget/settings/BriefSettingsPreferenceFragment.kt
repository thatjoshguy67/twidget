package com.tjg.twidget.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
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
        val preview = layoutInflater.inflate(R.layout.preference_brief_preview, null)
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
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "brief_refresh_on_home"
            title = getString(R.string.brief_refresh_on_home)
            summary = getString(R.string.brief_refresh_on_home_summary)
            isChecked = BriefSettingsStore.refreshOnHome(context)
            setOnPreferenceChangeListener { _, value ->
                BriefSettingsStore.setRefreshOnHome(context, value as Boolean)
                true
            }
        })

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
        screen.addPreference(SettingsListPreference(context).apply {
            key = "brief_provider_pref"
            title = getString(R.string.brief_provider)
            entries = arrayOf(
                getString(R.string.brief_provider_auto),
                getString(R.string.brief_provider_local),
                "Cloud",
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
            key = "brief_manage_cloud_providers"
            widgetLayoutResource = R.layout.preference_widget_debug_arrow
            title = getString(R.string.brief_manage_cloud_providers)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, SettingsCategoryActivity::class.java)
                    .putExtra(SettingsCategoryActivity.EXTRA_PAGE, SettingsPage.DATA.name))
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

    private fun providerLabel(provider: BriefProviderMode): String = if (provider == BriefProviderMode.CLOUD) "Cloud" else getString(
        when (provider) {
            BriefProviderMode.AUTO -> R.string.brief_provider_auto
            BriefProviderMode.LOCAL -> R.string.brief_provider_local
            BriefProviderMode.CLOUD -> R.string.brief_provider_cloud
        },
    )

    private fun spacerCategory() = PreferenceCategory(requireContext()).apply {
        isIconSpaceReserved = false
    }

}
