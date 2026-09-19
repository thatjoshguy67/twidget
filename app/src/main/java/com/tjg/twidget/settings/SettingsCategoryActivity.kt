package com.tjg.twidget.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tjg.twidget.R
import com.tjg.twidget.ui.FoldablePopOverActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout

internal enum class SettingsPage(val titleRes: Int) {
    ACCOUNTS(R.string.accounts),
    APPEARANCE(R.string.widget_appearance_section),
    DATA(R.string.settings_data_sources),
    SCHEDULING(R.string.scheduling),
}

class SettingsCategoryActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = page(intent.getStringExtra(EXTRA_PAGE))
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(if (page == SettingsPage.ACCOUNTS) R.string.social_accounts else page.titleRes))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(
                R.id.preference_fragment_container,
                if (page == SettingsPage.ACCOUNTS) com.tjg.twidget.social.SocialProfilesFragment() else SettingsCategoryPreferenceFragment().apply {
                    arguments = Bundle().apply { putString(EXTRA_PAGE, page.name) }
                },
            ).commit()
        }
    }

    companion object {
        internal const val EXTRA_PAGE = "settings_page"
        internal fun page(value: String?): SettingsPage =
            SettingsPage.entries.firstOrNull { it.name == value } ?: SettingsPage.ACCOUNTS
        internal fun intent(context: Context, page: SettingsPage): Intent =
            Intent(context, SettingsCategoryActivity::class.java).putExtra(EXTRA_PAGE, page.name)
    }
}
