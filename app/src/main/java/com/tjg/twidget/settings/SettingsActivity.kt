package com.tjg.twidget.settings

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.AboutActivity
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.startSettingsSubActivity
import com.google.android.material.appbar.AppBarLayout
import dev.oneuiproject.oneui.layout.ToolbarLayout

class SettingsActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyPreferenceScreenInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.settings))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
            val navigationIcon = toolbar.navigationIcon
            findAppBar(this)?.addOnOffsetChangedListener { _, verticalOffset ->
                toolbar.navigationIcon = if (verticalOffset == 0) null else navigationIcon
            }
            TwidgetStore.updateSuggestionVersion(this@SettingsActivity)?.let { version ->
                val updateCard = layoutInflater.inflate(
                    R.layout.preference_update_suggestion,
                    null,
                    false
                ).apply {
                    findViewById<android.widget.TextView>(R.id.update_suggestion_title).text =
                        getString(R.string.update_suggestion_title)
                    findViewById<android.widget.TextView>(R.id.update_suggestion_summary).text =
                        getString(R.string.update_suggestion_summary, version)
                    findViewById<android.widget.Button>(R.id.update_suggestion_action).apply {
                        text = getString(R.string.update)
                        setOnClickListener {
                            startSettingsSubActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
                        }
                    }
                    findViewById<android.widget.ImageButton>(R.id.update_suggestion_close)
                        .setOnClickListener {
                            TwidgetStore.dismissUpdateSuggestion(this@SettingsActivity)
                            recreate()
                        }
                }
                setCustomTitleView(updateCard)
                setExpanded(true, false)
                toolbar.navigationIcon = null
            }
        }
        if (savedInstanceState == null) {
            if (intent.getStringExtra(EXTRA_SCROLL_TO_PREFERENCE) == PREFERENCE_SCHEDULING) {
                startSettingsSubActivity(SettingsCategoryActivity.intent(this, SettingsPage.SCHEDULING))
            }
            val fragment = SettingsPreferenceFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, fragment)
                .commit()
        }
    }

    companion object {
        const val EXTRA_SCROLL_TO_PREFERENCE = "settings_scroll_to_preference"
        const val PREFERENCE_SCHEDULING = "scheduling_category"
    }

    private fun findAppBar(view: View): AppBarLayout? = when (view) {
        is AppBarLayout -> view
        is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { index -> findAppBar(view.getChildAt(index)) }
        else -> null
    }
}
