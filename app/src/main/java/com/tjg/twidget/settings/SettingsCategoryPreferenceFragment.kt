package com.tjg.twidget.settings

import com.tjg.twidget.core.UiMessages
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsImportActivity
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.env.BuildVars
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.startAddAccountActivity
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.widget.RefreshWorker
import com.tjg.twidget.widget.TwidgetBriefWidget
import com.tjg.twidget.widget.TwidgetWidget
import com.tjg.twidget.widget.WidgetPreviews
import dev.oneuiproject.oneui.preference.LayoutPreference

import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.appcompat.app.AlertDialog
import dev.oneuiproject.oneui.widget.RadioItemView
import dev.oneuiproject.oneui.widget.RadioItemViewGroup
import dev.oneuiproject.oneui.preference.HorizontalRadioPreference
import com.tjg.twidget.ui.AppAppearance
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.widget.TipPopup
import dev.oneuiproject.oneui.utils.DeviceLayoutUtil
import dev.oneuiproject.oneui.design.R as OneUiR

class SettingsCategoryPreferenceFragment : InsetPreferenceFragment() {
    private lateinit var settings: TwidgetSettings
    private var fontTip: TipPopup? = null
    private var accountRemovalBusy = false
    private var appearancePreview = AppearanceThemePreview.PHONE
    private val page get() = SettingsCategoryActivity.page(arguments?.getString(SettingsCategoryActivity.EXTRA_PAGE))

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = TwidgetStore.PREFS
        buildScreen()
    }

    override fun onPause() {
        dismissFontTip()
        super.onPause()
    }

    override fun onDestroyView() {
        dismissFontTip()
        super.onDestroyView()
    }

    private fun dismissFontTip() {
        fontTip?.dismiss(false)
        fontTip = null
    }

    private fun showFontTip(anchor: View) {
        dismissFontTip()
        if (!anchor.isAttachedToWindow) return
        fontTip = TipPopup(anchor).apply {
            setMessage(getString(R.string.settings_font_tip_summary))
            setAction(getString(R.string.settings_font_tip_link)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fahadalijaved/SamFonts")))
            }
            setExpanded(true)
            setOutsideTouchEnabled(true)
            show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles display changes itself, so reload the image arrays here.
        if (page == SettingsPage.APPEARANCE) buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    // The pinned SESL adapter is restricted to its library group, but extending it
    // through the fragment's adapter hook preserves its binding and rounded cards.
    // HorizontalRadioPreference is final and offers no preview-size/spacing API.
    @SuppressLint("RestrictedApi")
    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        if (page != SettingsPage.APPEARANCE) return super.onCreateAdapter(preferenceScreen)
        return object : PreferenceGroupAdapter(preferenceScreen) {
            override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                if (getItem(position)?.key != "settings_theme") return
                // Match Samsung's preview sizing and spacing without replacing the
                // library's selection, disabled-state, or accessibility behaviour.
                for (id in intArrayOf(OneUiR.id.item1, OneUiR.id.item2)) {
                    val item = holder.itemView.findViewById<View>(id)
                    item.findViewById<LinearLayout>(OneUiR.id.image_frame).gravity = Gravity.CENTER_HORIZONTAL
                    item.findViewById<ImageView>(OneUiR.id.icon).apply {
                        adjustViewBounds = true
                        maxWidth = dp(appearancePreview.widthDp)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = layoutParams.apply {
                            width = ViewGroup.LayoutParams.WRAP_CONTENT
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                    }
                    item.findViewById<View>(OneUiR.id.icon_title).apply {
                        layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                            topMargin = dp(10)
                        }
                    }
                    item.findViewById<View>(OneUiR.id.radio_button).apply {
                        layoutParams = (layoutParams as ViewGroup.MarginLayoutParams).apply {
                            topMargin = dp(4)
                        }
                    }
                }
            }
        }
    }

    private fun buildScreen() {
        dismissFontTip()
        val context = requireContext()
        settings = TwidgetStore.settings(context)
        val screen = preferenceManager.createPreferenceScreen(context)
        when (page) {
            SettingsPage.ACCOUNTS -> accounts(screen)
            SettingsPage.DATA -> data(screen)
            SettingsPage.APPEARANCE -> appearance(screen)
            SettingsPage.WIDGETS -> WidgetSettingsCards.populate(this, screen)
        }
        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun data(screen: androidx.preference.PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(category(R.string.analytics))
        screen.addPreference(SettingsListPreference(context).apply {
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
        screen.addDescribedPreference(SwitchPreferenceCompat(context).apply {
            key = "share_history_pref"
            title = getString(R.string.share_history)
            summary = getString(R.string.share_history_summary)
            isChecked = settings.shareHistory
            setOnPreferenceChangeListener { _, value ->
                save(settings.copy(shareHistory = value as Boolean))
                true
            }
        })

        screen.addPreference(category(R.string.settings_alternate_sources))
        listOf(
            TwidgetStore.DATA_SOURCE_SELF_HOSTED to R.string.settings_self_hosted_bridge,
            TwidgetStore.DATA_SOURCE_TWITTERAPIS to R.string.source_twitterapis,
            TwidgetStore.DATA_SOURCE_X_API to R.string.source_x_api,
        ).forEach { (source, label) ->
            screen.addPreference(Preference(context).apply {
                key = "source_$source"
                setTitle(label)
                setOnPreferenceClickListener {
                    requireActivity().startSettingsSubActivity(
                        Intent(context, SettingsAdvancedActivity::class.java)
                            .putExtra(SettingsAdvancedActivity.EXTRA_SOURCE, source),
                    )
                    true
                }
            })
        }
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

        screen.addPreference(InsetPreferenceCategory(context))
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
    }

    private fun accounts(screen: PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(category(R.string.settings_main_account))
        screen.addPreference(account(settings.username, true))
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(Preference(context).apply {
            key = "analytics_import"
            setTitle(R.string.settings_analytics_import)
            setIcon(R.drawable.ic_settings_download)
            summary = getString(if (TwidgetStore.currentStats(context, settings.username).history.any { it.imported })
                R.string.settings_imported else R.string.settings_import_pending)
            setOnPreferenceClickListener { beginAnalyticsImport(settings.username); true }
        })
        val others = TwidgetStore.accounts(context).filter {
            it.isNotBlank() && !it.equals(settings.username, true)
        }.distinctBy { it.lowercase() }
        if (others.isNotEmpty()) {
            screen.addPreference(category(R.string.settings_other_accounts))
            others.forEach { screen.addPreference(account(it, false)) }
        }
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(Preference(context).apply {
            key = "add_account"
            setTitle(R.string.add_account)
            setIcon(R.drawable.ic_settings_add)
            setOnPreferenceClickListener { requireActivity().startAddAccountActivity(); true }
        })
    }

    private fun account(username: String, isDefault: Boolean): Preference {
        val context = requireContext()
        val stats = TwidgetStore.currentStats(context, username)
        val row = CardItemView(context).apply {
            minimumHeight = resources.getDimensionPixelSize(R.dimen.settings_account_min_height)
            gravity = android.view.Gravity.CENTER_VERTICAL
            title = stats.fullName.ifBlank { username }
            summary = getString(R.string.account_handle, username.trimStart('@'))
            iconSize = dp(34)
            icon = context.getDrawable(R.drawable.avatar_twidget)
            ProfileImageLoader.loadInto(context, getIconImageView(), stats.profileImage)
            setOnClickListener {
                if (isDefault) showAccountActions(this, username, true) else {
                    save(settings.copy(username = username))
                    buildScreen()
                }
            }
        }
        fun attachLongClick(view: View) {
            view.setOnLongClickListener { showAccountActions(row, username, isDefault); true }
            if (view is ViewGroup) for (i in 0 until view.childCount) attachLongClick(view.getChildAt(i))
        }
        attachLongClick(row)
        return LayoutPreference(context, row).apply {
            key = "account_${username.lowercase()}"
            setAllowDividerAbove(true)
            setAllowDividerBelow(true)
        }
    }

    private fun showAccountActions(anchor: View, username: String, isDefault: Boolean) {
        val actions = accountPopupActions(isDefault)
        val labels = actions.map { action -> getString(when (action) {
            AccountPopupAction.SET_DEFAULT -> R.string.set_as_default
            AccountPopupAction.IMPORT_ANALYTICS -> R.string.import_x_analytics
            AccountPopupAction.CHANGE_HANDLE -> R.string.change_account_handle
            AccountPopupAction.DELETE -> R.string.delete
        }) }.toTypedArray()
        showSettingsDropdown(anchor, labels.toList()) { index ->
            when (actions[index]) {
                AccountPopupAction.SET_DEFAULT -> {
                    save(settings.copy(username = username))
                    buildScreen()
                }
                AccountPopupAction.IMPORT_ANALYTICS -> beginAnalyticsImport(username)
                AccountPopupAction.CHANGE_HANDLE -> changeAccountHandle(username)
                AccountPopupAction.DELETE -> {
                    if (TwidgetStore.accounts(requireContext()).size <= 1) {
                        Toast.makeText(requireContext(), R.string.cannot_delete_last_account, Toast.LENGTH_SHORT).show()
                    } else {
                        if (!accountRemovalBusy) {
                            accountRemovalBusy = true
                            val appContext = requireContext().applicationContext
                            val handler = android.os.Handler(android.os.Looper.getMainLooper())
                            com.tjg.twidget.core.AppExecutors.execute(onRejected = {
                                accountRemovalBusy = false
                                Toast.makeText(appContext, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                            }) {
                                val result = runCatching {
                                    TwidgetStore.removeAccount(appContext, username)
                                    TwidgetWidget.updateAll(appContext)
                                    TwidgetBriefWidget.updateAll(appContext)
                                }
                                handler.post {
                                    accountRemovalBusy = false
                                    if (isAdded) {
                                        if (result.isFailure) Toast.makeText(appContext, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                                        buildScreen()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun changeAccountHandle(username: String) {
        val context = requireContext()
        val input = android.widget.EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(username)
            selectAll()
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.change_account_handle)
            .setMessage(R.string.change_account_handle_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = input.text.toString().trim().trimStart('@')
                if (!next.matches(Regex("[A-Za-z0-9_]{1,15}"))) {
                    input.error = getString(R.string.invalid_account_handle)
                    return@setOnClickListener
                }
                if (accountRemovalBusy) return@setOnClickListener
                accountRemovalBusy = true
                val generation = TwidgetStore.accountGeneration(context, username)
                val appContext = context.applicationContext
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                com.tjg.twidget.core.AppExecutors.execute(onRejected = {
                    accountRemovalBusy = false
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                }) {
                    val result = runCatching {
                        val profile = com.tjg.twidget.providers.RettiwtClient.refresh(appContext, next)
                        TwidgetStore.migrateAccount(appContext, username, profile, generation, allowLegacy = true)
                        TwidgetWidget.updateAll(appContext)
                        TwidgetBriefWidget.updateAll(appContext)
                    }
                    handler.post {
                        accountRemovalBusy = false
                        if (isAdded) {
                            if (result.isSuccess) {
                                dialog.dismiss()
                                Toast.makeText(appContext, R.string.account_handle_changed, Toast.LENGTH_LONG).show()
                                buildScreen()
                            } else {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                input.error = UiMessages.resolve(requireContext(), result.exceptionOrNull()?.message, R.string.sync_failed)
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun beginAnalyticsImport(username: String) {
        startActivity(Intent(requireContext(), AnalyticsImportActivity::class.java)
            .putExtra(AnalyticsImportActivity.EXTRA_USERNAME, username))
    }

    private fun appearanceThemePreview(): AppearanceThemePreview {
        // Maximum bounds describe the active display even in split screen or a Samsung
        // pop-over. A narrow window on an unfolded device should still preview a wide UI.
        val smallestDisplayWidthDp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = requireActivity().windowManager.maximumWindowMetrics.bounds
            minOf(bounds.width(), bounds.height()) / resources.displayMetrics.density
        } else {
            resources.configuration.smallestScreenWidthDp.toFloat()
        }
        return AppearanceThemePreview.forDevice(
            smallestDisplayWidthDp = smallestDisplayWidthDp,
            isTabletDevice = DeviceLayoutUtil.isTabletCategoryOrBuild(requireContext()),
            hasHinge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE),
            isSamsungDevice = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        )
    }

    private fun appearance(screen: PreferenceScreen) {
        val context = requireContext()
        // Inflate the library component so its image and entry arrays use its supported XML API.
        appearancePreview = appearanceThemePreview()
        setPreferencesFromResource(appearancePreview.preferenceResource, null)
        val themeScreen = preferenceScreen
        val picker = themeScreen.findPreference<HorizontalRadioPreference>("settings_theme")!!
        val followSystem = themeScreen.findPreference<SwitchPreferenceCompat>("settings_theme_system")!!
        themeScreen.removePreference(picker)
        themeScreen.removePreference(followSystem)
        picker.isPersistent = false
        val mode = AppAppearance.mode(context)
        picker.value = if (mode == AppCompatDelegate.MODE_NIGHT_YES) "dark" else if (mode == AppCompatDelegate.MODE_NIGHT_NO) "light"
            else if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        picker.isEnabled = mode != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        picker.setOnPreferenceChangeListener { _, value ->
            AppAppearance.setMode(context, if (value == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            true
        }
        followSystem.isPersistent = false
        followSystem.isChecked = mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        followSystem.setOnPreferenceChangeListener { _, value ->
            AppAppearance.setMode(context, if (value == true) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else if (picker.value == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            listView.post { if (isAdded) buildScreen() }
            true
        }
        // Detached XML preferences retain their old order; let the destination assign it.
        picker.order = Preference.DEFAULT_ORDER
        followSystem.order = Preference.DEFAULT_ORDER
        screen.addPreference(picker)
        screen.addPreference(followSystem)
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(object : SettingsListPreference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                holder.findViewById(R.id.settings_font_tip_button)?.setOnClickListener(::showFontTip)
            }
        }.apply {
            key = "settings_app_font"
            if (BuildVars.DISTRIBUTION.isGithub() && TwidgetFonts.hasSystemOneUiSans) {
                widgetLayoutResource = R.layout.preference_font_tip
            }
            isPersistent = false
            setTitle(R.string.settings_app_font)
            setDialogTitle(R.string.settings_app_font)
            entries = arrayOf(getString(R.string.settings_app_font_default),
                getString(R.string.widget_font_google), getString(R.string.widget_font_system))
            entryValues = AppAppearance.Font.entries.map { it.value }.toTypedArray()
            value = AppAppearance.font(context).value
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                val font = AppAppearance.Font.entries.first { it.value == value }
                if (font != AppAppearance.font(context)) {
                    AppAppearance.setFont(context, font)
                    listView.post { if (isAdded) requireActivity().recreate() }
                }
                true
            }
        })
        val logoValues = arrayOf(TwidgetStore.LOGO_X, TwidgetStore.LOGO_TWITTER)
        val logoLabels = arrayOf(getString(R.string.widget_logo_x), getString(R.string.widget_logo_twitter))
        val logoRow = CardItemView(context).apply { title = getString(R.string.settings_app_logo_style) }
        fun displayLogo(value: String) {
            logoRow.summary = ""
            logoRow.getEndImageView().setImageResource(
                if (value == TwidgetStore.LOGO_TWITTER) R.drawable.ic_settings_twitter else R.drawable.ic_logo_x,
            )
        }
        displayLogo(AppAppearance.logo(context))
        val logoPreference = LayoutPreference(context, logoRow).apply {
            key = "settings_app_logo"
            setTitle(R.string.settings_app_logo_style)
            setAllowDividerAbove(true)
            setAllowDividerBelow(true)
            setOnPreferenceChangeListener { _, value ->
                val logo = value as String
                AppAppearance.setLogo(context, logo)
                WidgetPreviews.publish(context)
                displayLogo(logo)
                true
            }
        }
        val openLogoPicker = {
            showSettingsDropdown(logoRow, logoLabels.toList(), logoValues.indexOf(AppAppearance.logo(context))) { index ->
                logoPreference.callChangeListener(logoValues[index])
            }
        }
        logoRow.setOnClickListener { openLogoPicker() }
        logoRow.setOnLongClickListener { openLogoPicker(); true }
        logoPreference.setOnPreferenceClickListener { openLogoPicker(); true }
        screen.addPreference(logoPreference)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun category(titleRes: Int): PreferenceCategory = PreferenceCategory(requireContext()).apply {
        if (titleRes != 0) setTitle(titleRes)
        isIconSpaceReserved = false
    }

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

}
