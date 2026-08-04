package com.tjg.twidget.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsImportActivity
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleSettingsStore
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.ProgressiveBlurChrome
import com.tjg.twidget.ui.TwidgetTheme
import com.tjg.twidget.ui.startAddAccountActivity
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.widget.RefreshWorker
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.preference.SuggestionCardPreference

class SettingsPreferenceFragment : InsetPreferenceFragment() {
    private lateinit var settings: TwidgetSettings

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        settings = TwidgetStore.settings(requireContext())
        preferenceManager.sharedPreferencesName = TwidgetStore.PREFS
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        settings = TwidgetStore.settings(requireContext())
        buildScreen()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.getString(ARG_SCROLL_TO_PREFERENCE)?.let { preferenceKey ->
            listView.post {
                val position = (listView.adapter as? PreferenceGroup.PreferencePositionCallback)
                    ?.getPreferenceAdapterPosition(preferenceKey)
                    ?: return@post
                (listView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(position, 0)
            }
        }
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        TwidgetStore.updateSuggestionVersion(context)?.let { version ->
            screen.addPreference(SuggestionCardPreference(context).apply {
                key = "update_suggestion"
                title = getString(R.string.update_suggestion_title)
                summary = getString(R.string.update_suggestion_summary, version)
                setActionButtonText(getString(R.string.update))
                setActionButtonOnClickListener {
                    requireActivity().startSettingsSubActivity(Intent(context, AboutActivity::class.java))
                }
                setOnClosedClickedListener {
                    TwidgetStore.dismissUpdateSuggestion(context)
                }
            })
        }

        screen.addPreference(category(R.string.appearance))
        screen.addPreference(ListPreference(context).apply {
            key = "theme_mode_pref"
            title = getString(R.string.theme_section)
            dialogTitle = getString(R.string.theme_section)
            summary = TwidgetTheme.themeModeLabel(context, TwidgetStore.appThemeMode(context))
            entries = arrayOf(
                getString(R.string.theme_mode_auto),
                getString(R.string.theme_mode_light),
                getString(R.string.theme_mode_dark),
            )
            entryValues = arrayOf(
                TwidgetStore.COLOR_MODE_SYSTEM,
                TwidgetStore.COLOR_MODE_LIGHT,
                TwidgetStore.COLOR_MODE_DARK,
            )
            value = TwidgetStore.appThemeMode(context)
            setOnPreferenceChangeListener { pref, value ->
                val mode = value as String
                TwidgetStore.saveAppThemeMode(context, mode)
                pref.summary = TwidgetTheme.themeModeLabel(context, mode)
                TwidgetTheme.publishChange(context)
                refreshAppearancePreferences()
                true
            }
        })
        screen.addPreference(AccentColorPreference(context).apply {
            key = "accent_color_pref"
            title = getString(R.string.accent_color_section)
        })
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "progressive_blur_pref"
            title = getString(R.string.blur_effects)
            summary = getString(R.string.blur_effects_summary)
            isChecked = TwidgetStore.appBlurEnabled(context)
            setOnPreferenceChangeListener { _, value ->
                TwidgetStore.saveAppBlurEnabled(context, value as Boolean)
                ProgressiveBlurChrome.refreshFromRoot(requireActivity())
                true
            }
        })

        screen.addPreference(category(R.string.accounts))
        trackedAccounts().forEach { username ->
            screen.addPreference(
                AccountPreference(
                    context = context,
                    accountUsername = username,
                    onLongPress = { anchor ->
                        showAccountPopup(anchor, username)
                    },
                ),
            )
        }
        screen.addPreference(Preference(context).apply {
            key = "add_account"
            title = getString(R.string.add_account)
            setOnPreferenceClickListener {
                requireActivity().startAddAccountActivity()
                true
            }
        })

        screen.addPreference(category(R.string.refresh))
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "refresh_on_launch_pref"
            title = getString(R.string.refresh_on_launch)
            isChecked = settings.refreshOnLaunch
            setOnPreferenceChangeListener { _, value ->
                save(settings.copy(refreshOnLaunch = value as Boolean))
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "refresh_interval_pref"
            title = getString(R.string.refresh_interval)
            text = settings.refreshIntervalMinutes.toString()
            summary = getString(R.string.refresh_interval_value, settings.refreshIntervalMinutes)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val minutes = (value as String).toIntOrNull()?.coerceIn(15, 240) ?: 15
                save(settings.copy(refreshIntervalMinutes = minutes))
                RefreshWorker.schedule(requireContext())
                (pref as EditTextPreference).summary = getString(R.string.refresh_interval_value, minutes)
                true
            }
        })

        screen.addPreference(category(R.string.analytics))
        screen.addPreference(ListPreference(context).apply {
            key = "data_source_pref"
            title = getString(R.string.active_source)
            dialogTitle = getString(R.string.active_source)
            summary = dataSourceTitle(settings.dataSource)
            entries = arrayOf(
                getString(R.string.source_fxtwitter),
                getString(R.string.source_default),
                getString(R.string.source_self_hosted),
                getString(R.string.source_x_api),
                getString(R.string.source_twitterapis),
            )
            entryValues = arrayOf(
                TwidgetStore.DATA_SOURCE_FXTWITTER,
                TwidgetStore.DATA_SOURCE_DEFAULT,
                TwidgetStore.DATA_SOURCE_SELF_HOSTED,
                TwidgetStore.DATA_SOURCE_X_API,
                TwidgetStore.DATA_SOURCE_TWITTERAPIS,
            )
            value = settings.dataSource
            setOnPreferenceChangeListener { pref, value ->
                val source = value as String
                save(settings.copy(dataSource = source))
                pref.summary = dataSourceTitle(source)
                true
            }
        })
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "share_history_pref"
            title = getString(R.string.share_history)
            summary = getString(R.string.share_history_summary)
            isChecked = settings.shareHistory
            setOnPreferenceChangeListener { _, value ->
                save(settings.copy(shareHistory = value as Boolean))
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "advanced_options"
            title = getString(R.string.advanced_options)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, SettingsAdvancedActivity::class.java))
                true
            }
        })

        screen.addPreference(category(R.string.scheduling).apply {
            key = SettingsActivity.PREFERENCE_SCHEDULING
        })
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "schedule_notifications"
            title = getString(R.string.enable_notifications)
            isPersistent = false
            isChecked = NotificationManagerCompat.from(context).areNotificationsEnabled()
            setOnPreferenceChangeListener { _, _ ->
                startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                )
                false
            }
        })
        screen.addPreference(ListPreference(context).apply {
            key = "schedule_default_method_pref"
            title = getString(R.string.scheduling_method)
            entries = arrayOf(
                getString(R.string.schedule_provider_local),
                getString(R.string.schedule_provider_buffer),
            )
            entryValues = arrayOf(
                ScheduleProvider.LOCAL_REMINDER.name,
                ScheduleProvider.BUFFER.name,
            )
            val selected = ScheduleSettingsStore.defaultProvider(context)
            value = selected.name
            summary = providerLabel(selected)
            setOnPreferenceChangeListener { preference, value ->
                val provider = ScheduleProvider.valueOf(value as String)
                ScheduleSettingsStore.setDefaultProvider(context, provider)
                preference.summary = providerLabel(provider)
                screen.findPreference<Preference>("buffer_settings")?.isVisible =
                    provider == ScheduleProvider.BUFFER
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "buffer_settings"
            title = getString(R.string.buffer_settings)
            isVisible = ScheduleSettingsStore.defaultProvider(context) == ScheduleProvider.BUFFER
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, SettingsScheduleActivity::class.java))
                true
            }
        })

        screen.addPreference(category(0))
        screen.addPreference(Preference(context).apply {
            key = "clear_cached_stats"
            title = SpannableString(getString(R.string.clear_cache)).apply {
                setSpan(ForegroundColorSpan(context.getColor(R.color.metric_red)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setOnPreferenceClickListener {
                TwidgetStore.clearCachedStats(context)
                TwidgetWidget.updateAll(context)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "about_twidget"
            title = getString(R.string.about_twidget)
            // Native SESL update badge, as on the About rows of Samsung apps.
            dotVisibility = TwidgetStore.updateAvailable(context)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, AboutActivity::class.java))
                true
            }
        })
        // Hidden until the version number in About has been tapped seven times.
        if (TwidgetStore.debugMenuUnlocked(context)) {
            screen.addPreference(Preference(context).apply {
                key = "debug_menu"
                title = getString(R.string.debug_menu)
                setOnPreferenceClickListener {
                    requireActivity().startSettingsSubActivity(Intent(context, SettingsDebugActivity::class.java))
                    true
                }
            })
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun category(titleRes: Int): PreferenceCategory =
        PreferenceCategory(requireContext()).apply {
            if (titleRes != 0) title = getString(titleRes)
            isIconSpaceReserved = false
        }

    private fun refreshAppearancePreferences() {
        val context = requireContext()
        findPreference<ListPreference>("theme_mode_pref")?.summary =
            TwidgetTheme.themeModeLabel(context, TwidgetStore.appThemeMode(context))
        (findPreference("accent_color_pref") as? AccentColorPreference)?.refreshFromStore()
        listView.adapter?.notifyDataSetChanged()
    }

    companion object {
        const val ARG_SCROLL_TO_PREFERENCE = "scroll_to_preference"
    }

    private fun trackedAccounts(): List<String> {
        val context = requireContext()
        return TwidgetStore.accounts(context)
            .ifEmpty { listOf(settings.username) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    private fun showAccountPopup(anchor: View, username: String) {
        val context = requireContext()
        val isDefault = username.equals(settings.username, ignoreCase = true)
        val actions = accountPopupActions(isDefault)
        val labels = actions.map { action ->
            getString(
                when (action) {
                    AccountPopupAction.SET_DEFAULT -> R.string.set_as_default
                    AccountPopupAction.IMPORT_ANALYTICS -> R.string.import_x_analytics
                    AccountPopupAction.DELETE -> R.string.delete
                }
            )
        }
        val popup = ListPopupWindow(context).apply {
            setAdapter(object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(
                            if (actions[position] == AccountPopupAction.DELETE) {
                                context.getColor(R.color.metric_red)
                            } else {
                                TwidgetTheme.textPrimary(context)
                            }
                        )
                    }
            })
            anchorView = anchor
            width = dp(220)
            // Samsung's popup measurement can collapse a multi-row adapter to
            // its final row. Give every action a fixed native menu-row slot.
            height = dp(56 * actions.size)
            isModal = true
            horizontalOffset = dp(40)
            verticalOffset = -dp(24)
        }
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            when (actions[position]) {
                AccountPopupAction.SET_DEFAULT -> {
                    save(settings.copy(username = username))
                    buildScreen()
                }
                AccountPopupAction.IMPORT_ANALYTICS -> beginAnalyticsImport(username)
                AccountPopupAction.DELETE -> deleteAccount(username)
            }
        }
        popup.show()
    }

    private fun beginAnalyticsImport(username: String) {
        startActivity(
            Intent(requireContext(), AnalyticsImportActivity::class.java)
                .putExtra(AnalyticsImportActivity.EXTRA_USERNAME, username)
        )
    }

    private fun deleteAccount(username: String) {
        val context = requireContext()
        if (TwidgetStore.accounts(context).size <= 1) {
            Toast.makeText(context, R.string.cannot_delete_last_account, Toast.LENGTH_SHORT).show()
            return
        }
        TwidgetStore.removeAccount(context, username)
        settings = TwidgetStore.settings(context)
        TwidgetWidget.updateAll(context)
        buildScreen()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun save(next: TwidgetSettings) {
        settings = next
        TwidgetStore.saveSettings(requireContext(), next)
        TwidgetWidget.updateAll(requireContext())
    }

    private fun dataSourceTitle(source: String): String = when (source) {
        TwidgetStore.DATA_SOURCE_FXTWITTER -> getString(R.string.source_fxtwitter)
        TwidgetStore.DATA_SOURCE_SELF_HOSTED -> getString(R.string.source_self_hosted)
        TwidgetStore.DATA_SOURCE_X_API -> getString(R.string.source_x_api)
        TwidgetStore.DATA_SOURCE_TWITTERAPIS -> getString(R.string.source_twitterapis)
        else -> getString(R.string.source_default)
    }

    private fun providerLabel(provider: ScheduleProvider): String = getString(
        if (provider == ScheduleProvider.BUFFER) {
            R.string.schedule_provider_buffer
        } else {
            R.string.schedule_provider_local
        }
    )

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is AccountPreference) {
            val username = preference.accountUsername
            if (!username.equals(settings.username, ignoreCase = true)) {
                save(settings.copy(username = username))
                buildScreen()
            }
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }
}

internal enum class AccountPopupAction {
    SET_DEFAULT,
    IMPORT_ANALYTICS,
    DELETE,
}

internal fun accountPopupActions(isDefault: Boolean): List<AccountPopupAction> = buildList {
    if (!isDefault) add(AccountPopupAction.SET_DEFAULT)
    add(AccountPopupAction.IMPORT_ANALYTICS)
    add(AccountPopupAction.DELETE)
}
