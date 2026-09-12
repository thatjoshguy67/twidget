package com.tjg.twidget.settings

import android.os.Bundle
import com.tjg.twidget.R
import com.tjg.twidget.ui.FoldablePopOverActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout

class BriefSettingsActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.brief_settings_title))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, BriefSettingsPreferenceFragment())
                .commit()
        }
    }
}
