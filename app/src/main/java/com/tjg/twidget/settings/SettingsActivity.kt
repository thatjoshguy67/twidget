package com.tjg.twidget.settings

import android.os.Bundle
import com.tjg.twidget.R
import com.tjg.twidget.ui.FoldablePopOverActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout

class SettingsActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.settings))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            val fragment = SettingsPreferenceFragment().apply {
                arguments = Bundle().apply {
                    putString(
                        SettingsPreferenceFragment.ARG_SCROLL_TO_PREFERENCE,
                        intent.getStringExtra(EXTRA_SCROLL_TO_PREFERENCE),
                    )
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, fragment)
                .commit()
        }
    }

    companion object {
        const val EXTRA_SCROLL_TO_PREFERENCE = "settings_scroll_to_preference"
        const val PREFERENCE_SCHEDULING = "scheduling_category"
    }
}
