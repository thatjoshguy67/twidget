package com.tjg.twidget.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsImportActivity
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleSettingsStore
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.VerifiedBadge
import com.tjg.twidget.ui.startAddAccountActivity
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.widget.RefreshWorker
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.R as IconR
import dev.oneuiproject.oneui.preference.LayoutPreference
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

        screen.addPreference(category(R.string.accounts))
        screen.addPreference(LayoutPreference(context, accountsCard()).apply {
            key = "accounts_card"
            isSelectable = false
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

        screen.addPreference(category(R.string.scheduling))
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

    private fun accountsCard(): View {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(context.getColor(R.color.oneui_card_bg))
            }
            clipToOutline = true
        }

        val accounts = TwidgetStore.accounts(context)
            .ifEmpty { listOf(settings.username) }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        accounts.forEachIndexed { index, username ->
            card.addView(accountRow(username))
            card.addView(divider(startMargin = dp(78)))
        }
        card.addView(addAccountRow())
        return card
    }

    private fun accountRow(username: String): View {
        val context = requireContext()
        val stats = TwidgetStore.currentStats(context, username)
        val isDefault = username.equals(settings.username, ignoreCase = true)
        var popupAnchor: View? = null
        val longClickListener = View.OnLongClickListener { anchor ->
            showAccountPopup(popupAnchor ?: anchor, username, isDefault)
            true
        }

        return LinearLayout(context).apply {
            popupAnchor = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Native SESL two-line preference rows measure 70dp tall with
            // content inset 18dp from the card edge.
            minimumHeight = dp(70)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            isClickable = true
            isFocusable = true
            setBackgroundResource(resolveSelectableItemBackground())
            setOnClickListener {
                save(settings.copy(username = username))
                buildScreen()
            }
            setOnLongClickListener(longClickListener)

            addView(ImageView(context).apply {
                setBackgroundResource(R.drawable.avatar_twidget)
                ProfileImageLoader.loadInto(context, this, stats.profileImage)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    // Native SESL list sizes (17sp title / 13sp secondary) so
                    // the card reads consistently with the preference rows.
                    text = VerifiedBadge.decorate(context, stats.fullName.ifBlank { username }, stats.isVerified, stats.isPrivate, dp(16))
                    setTextColor(context.getColor(R.color.oneui_text_primary))
                    textSize = 17f
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                })
                addView(TextView(context).apply {
                    text = context.getString(R.string.account_handle, username.trimStart('@'))
                    setTextColor(context.getColor(R.color.oneui_text_secondary))
                    textSize = 13f
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
                marginEnd = dp(10)
            })

            addView(ImageView(context).apply {
                setImageResource(if (isDefault) IconR.drawable.ic_oui_favorite_on else IconR.drawable.ic_oui_favorite_off)
                imageTintList = ColorStateList.valueOf(
                    context.getColor(if (isDefault) R.color.oneui_accent else R.color.oneui_text_secondary)
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, LinearLayout.LayoutParams(dp(42), dp(42)))

            attachLongClickToChildren(this, longClickListener)
        }
    }

    private fun attachLongClickToChildren(view: View, listener: View.OnLongClickListener) {
        view.setOnLongClickListener(listener)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                attachLongClickToChildren(view.getChildAt(index), listener)
            }
        }
    }

    private fun showAccountPopup(anchor: View, username: String, isDefault: Boolean) {
        val context = requireContext()
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
                            context.getColor(
                                if (actions[position] == AccountPopupAction.DELETE) {
                                    R.color.metric_red
                                } else {
                                    R.color.oneui_text_primary
                                }
                            )
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

    private fun addAccountRow(): View =
        TextView(requireContext()).apply {
            text = getString(R.string.add_account)
            setTextColor(context.getColor(R.color.oneui_text_primary))
            textSize = 17f
            typeface = Typeface.create("sec", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            // Native single-line preference rows measure 56dp.
            minHeight = dp(56)
            setPadding(dp(18), 0, dp(18), 0)
            isClickable = true
            isFocusable = true
            setBackgroundResource(resolveSelectableItemBackground())
            setOnClickListener {
                requireActivity().startAddAccountActivity()
            }
        }

    private fun divider(startMargin: Int): View =
        View(requireContext()).apply {
            setBackgroundColor(requireContext().getColor(R.color.oneui_divider))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                marginStart = startMargin
                marginEnd = dp(18)
            }
        }

    private fun resolveSelectableItemBackground(): Int {
        val typed = android.util.TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
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
