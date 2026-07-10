package com.tjg.twidget

import android.os.Bundle
import dev.oneuiproject.oneui.layout.ToolbarLayout

class SettingsDebugActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.debug_menu))
            setNavigationButtonOnClickListener { finish() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, SettingsDebugPreferenceFragment())
                .commit()
        }
    }
}
