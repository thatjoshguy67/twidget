package com.tjg.twidget

import android.os.Bundle
import dev.oneuiproject.oneui.layout.ToolbarLayout

class SettingsAdvancedActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.advanced_options))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, SettingsAdvancedPreferenceFragment())
                .commit()
        }
    }
}
