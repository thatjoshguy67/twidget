package com.tjg.twidget

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import dev.oneuiproject.oneui.preference.LayoutPreference
import dev.oneuiproject.oneui.R as IconR

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
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

        screen.addPreference(category(R.string.data_source))
        screen.addPreference(ListPreference(context).apply {
            key = "data_source_pref"
            title = getString(R.string.active_source)
            dialogTitle = getString(R.string.active_source)
            summary = dataSourceTitle(settings.dataSource)
            entries = arrayOf(
                getString(R.string.source_default),
                getString(R.string.source_fxtwitter),
                getString(R.string.source_self_hosted),
                getString(R.string.source_x_api),
            )
            entryValues = arrayOf(
                TwidgetStore.DATA_SOURCE_DEFAULT,
                TwidgetStore.DATA_SOURCE_FXTWITTER,
                TwidgetStore.DATA_SOURCE_SELF_HOSTED,
                TwidgetStore.DATA_SOURCE_X_API,
            )
            value = settings.dataSource
            setOnPreferenceChangeListener { pref, value ->
                val source = value as String
                save(settings.copy(dataSource = source))
                pref.summary = dataSourceTitle(source)
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
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, AboutActivity::class.java))
                true
            }
        })
        if (resources.getBoolean(R.bool.show_debug_onboarding)) {
            screen.addPreference(Preference(context).apply {
                key = "debug_onboarding"
                title = getString(R.string.test_onboarding)
                summary = getString(R.string.debug_only)
                setOnPreferenceClickListener {
                    startActivity(Intent(context, OnboardingActivity::class.java))
                    true
                }
            })
        }

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
            card.addView(divider(startMargin = dp(86)))
        }
        card.addView(addAccountRow())
        return card
    }

    private fun accountRow(username: String): View {
        val context = requireContext()
        val stats = TwidgetStore.currentStats(context, username)
        val isDefault = username.equals(settings.username, ignoreCase = true)

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(22), dp(10), dp(18), dp(10))
            isClickable = true
            isFocusable = true
            setBackgroundResource(resolveSelectableItemBackground())
            setOnClickListener {
                save(settings.copy(username = username))
                buildScreen()
            }
            setOnLongClickListener {
                showAccountPopup(this, username, isDefault)
                true
            }

            addView(ImageView(context).apply {
                setBackgroundResource(R.drawable.avatar_twidget)
                ProfileImageLoader.loadInto(context, this, stats.profileImage)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = VerifiedBadge.decorate(context, stats.fullName.ifBlank { username }, stats.isVerified, stats.isPrivate, dp(18))
                    setTextColor(context.getColor(R.color.oneui_text_primary))
                    textSize = 19f
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                })
                addView(TextView(context).apply {
                    text = "@${username.trimStart('@')}"
                    setTextColor(context.getColor(R.color.oneui_text_secondary))
                    textSize = 15f
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                    includeFontPadding = false
                    maxLines = 1
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(18)
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
        }
    }

    private fun showAccountPopup(anchor: View, username: String, isDefault: Boolean) {
        val context = requireContext()
        val setDefaultLabel = getString(R.string.set_as_default)
        val deleteLabel = getString(R.string.delete)
        val labels = buildList {
            if (!isDefault) add(setDefaultLabel)
            add(deleteLabel)
        }
        val popup = ListPopupWindow(context).apply {
            setAdapter(object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(
                            context.getColor(
                                if (labels[position] == deleteLabel) R.color.metric_red else R.color.oneui_text_primary
                            )
                        )
                    }
            })
            anchorView = anchor
            width = dp(220)
            height = ListPopupWindow.WRAP_CONTENT
            isModal = true
            horizontalOffset = dp(40)
            verticalOffset = -dp(24)
        }
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            when (labels[position]) {
                setDefaultLabel -> {
                    save(settings.copy(username = username))
                    buildScreen()
                }
                deleteLabel -> deleteAccount(username)
            }
        }
        popup.show()
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
            textSize = 19f
            typeface = Typeface.create("sec", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(66)
            setPadding(dp(22), 0, dp(22), 0)
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
        else -> getString(R.string.source_default)
    }
}
