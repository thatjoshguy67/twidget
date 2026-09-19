package com.tjg.twidget.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.preference.Preference
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.startSettingsSubActivity
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory
import dev.oneuiproject.oneui.preference.LayoutPreference
import dev.oneuiproject.oneui.preference.SuggestionCardPreference
import dev.oneuiproject.oneui.widget.CardItemView

class SettingsPreferenceFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) = buildScreen()

    override fun onResume() {
        super.onResume()
        SettingsLanguage.refreshWidgetsIfLanguageChanged(requireContext())
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val settings = TwidgetStore.settings(context)
        TwidgetStore.updateSuggestionVersion(context)?.let { version ->
            screen.addPreference(SuggestionCardPreference(context).apply {
                key = "update_suggestion"
                title = getString(R.string.update_suggestion_title)
                summary = getString(R.string.update_suggestion_summary, version)
                setActionButtonText(getString(R.string.update))
                setActionButtonOnClickListener { open(AboutActivity::class.java) }
                setOnClosedClickedListener { TwidgetStore.dismissUpdateSuggestion(context) }
            })
            screen.addPreference(InsetPreferenceCategory(context))
        }
        val stats = TwidgetStore.currentStats(context, settings.username)
        val catalog = com.tjg.twidget.social.SocialRepository(context).use { it.catalog() }
        val mainProfile = catalog.profiles.firstOrNull { it.id == catalog.defaultProfileId }
        val profile = CardItemView(context).apply {
            minimumHeight = resources.getDimensionPixelSize(R.dimen.settings_account_min_height)
            gravity = android.view.Gravity.CENTER_VERTICAL
            title = mainProfile?.displayName(catalog.accountsById) ?: stats.fullName.ifBlank { settings.username }
            summary = when {
                mainProfile?.linked == true -> getString(R.string.social_linked_profile)
                mainProfile != null -> "@${catalog.accountsById.getValue(mainProfile.nameAccountId).handle}"
                else -> getString(R.string.account_handle, settings.username.trimStart('@'))
            }
            iconSize = (34 * resources.displayMetrics.density).toInt()
            icon = context.getDrawable(R.drawable.avatar_twidget)
            ProfileImageLoader.loadInto(context, getIconImageView(), mainProfile?.avatarUrl(catalog.accountsById) ?: stats.profileImage)
            setOnClickListener { openCategory(SettingsPage.ACCOUNTS) }
        }
        screen.addPreference(LayoutPreference(context, profile).apply {
            key = "main_account"
            setAllowDividerBelow(true)
        })
        screen.addPreference(destination("accounts", R.string.social_accounts, R.drawable.settings_icon_accounts) {
            openCategory(SettingsPage.ACCOUNTS)
        })
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(destination("appearance", R.string.widget_appearance_section, R.drawable.settings_icon_appearance) {
            openCategory(SettingsPage.APPEARANCE)
        })
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(destination("data_sources", R.string.settings_data_sources, R.drawable.settings_icon_data) {
            openCategory(SettingsPage.DATA)
        })
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(destination("brief_settings_pref", R.string.settings_your_brief, R.drawable.settings_icon_brief, badge = com.tjg.twidget.brief.BriefSettingsStore.showSettingsBadge(context)) {
            open(BriefSettingsActivity::class.java)
        })
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(destination("notifications", R.string.settings_notifications, R.drawable.settings_icon_notifications) {
            startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName))
        })
        screen.addPreference(destination(SettingsActivity.PREFERENCE_SCHEDULING, R.string.settings_scheduled_tweets, R.drawable.settings_icon_schedule) {
            openCategory(SettingsPage.SCHEDULING)
        })
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(destination("app_language_pref", R.string.language, R.drawable.settings_icon_language) {
            SettingsLanguage.open(requireActivity())
        })
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(destination("about_twidget", R.string.about_twidget, R.drawable.settings_icon_about,
            badge = TwidgetStore.updateAvailable(context)) {
            open(AboutActivity::class.java)
        })
        if (TwidgetStore.debugMenuUnlocked(context)) {
            screen.addPreference(destination("debug_menu", R.string.debug_menu, R.drawable.settings_icon_debug) {
                open(SettingsDebugActivity::class.java)
            })
        }
        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun destination(keyName: String, titleRes: Int, iconRes: Int, badge: Boolean = false,
        action: () -> Unit): Preference {
        val context = requireContext()
        val row = CardItemView(context).apply {
            title = getString(titleRes)
            iconSize = (24 * resources.displayMetrics.density).toInt()
            icon = SettingsIcons.load(context, iconRes)
            showBadge = badge
            setOnClickListener { action() }
        }
        return LayoutPreference(context, row).apply {
            key = keyName
            setTitle(titleRes)
            setAllowDividerAbove(true)
            setAllowDividerBelow(true)
            setOnPreferenceClickListener { action(); true }
        }
    }

    private fun openCategory(page: SettingsPage) {
        requireActivity().startSettingsSubActivity(SettingsCategoryActivity.intent(requireContext(), page))
    }

    private fun open(activity: Class<out android.app.Activity>) {
        requireActivity().startSettingsSubActivity(Intent(requireContext(), activity))
    }
}
