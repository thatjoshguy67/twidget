package com.tjg.twidget.settings

import android.os.Bundle
import com.tjg.twidget.R
import com.tjg.twidget.ui.FoldablePopOverActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout

class SettingsAdvancedActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(when (intent.getStringExtra(EXTRA_SOURCE)) {
                com.tjg.twidget.data.TwidgetStore.DATA_SOURCE_TWITTERAPIS -> R.string.source_twitterapis
                com.tjg.twidget.data.TwidgetStore.DATA_SOURCE_X_API -> R.string.source_x_api
                else -> R.string.settings_self_hosted_bridge
            }))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, SettingsAdvancedPreferenceFragment().apply {
                    arguments = Bundle().apply { putString(EXTRA_SOURCE, intent.getStringExtra(EXTRA_SOURCE)) }
                })
                .commit()
        }
    }
    companion object {
        const val EXTRA_SOURCE = "settings_source"
    }
}
