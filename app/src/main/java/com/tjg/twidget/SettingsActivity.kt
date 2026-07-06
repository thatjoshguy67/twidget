package com.tjg.twidget

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.settings))
            setNavigationButtonOnClickListener { finish() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, SettingsPreferenceFragment())
                .commit()
        }
    }
}
