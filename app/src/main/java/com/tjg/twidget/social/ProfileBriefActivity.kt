package com.tjg.twidget.social

import android.os.Bundle
import androidx.preference.Preference
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import dev.oneuiproject.oneui.layout.ToolbarLayout

class ProfileBriefActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.brief_title)); setNavigationButtonOnClickListener { finish() }
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction().replace(R.id.preference_fragment_container, ProfileBriefFragment()).commit()
    }
    companion object { const val EXTRA_PROFILE = "social_profile_id" }
}

class ProfileBriefFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) { preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()) }
    override fun onResume() {
        super.onResume()
        val app = requireContext().applicationContext
        val requested = requireActivity().intent.getStringExtra(ProfileBriefActivity.EXTRA_PROFILE)
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        AppExecutors.execute {
            val result = runCatching {
                val id = requested ?: SocialRepository(app).use { it.catalog().defaultProfileId } ?: error("No profile")
                ProfileBriefEngine.rebuild(app, id)
            }
            ui.post {
                if (!isAdded) return@post
                val screen = preferenceManager.createPreferenceScreen(requireContext())
                result.onSuccess { brief ->
                    brief.cards.forEach { card -> screen.addPreference(Preference(requireContext()).apply {
                        title = card.title; summary = card.body + "\n" + card.sourceAttribution; isSelectable = false; isPersistent = false
                    }) }
                }.onFailure { screen.addPreference(Preference(requireContext()).apply { title = getString(R.string.social_load_failed); isSelectable = false }) }
                screen.addBottomInset(); preferenceScreen = screen
            }
        }
    }
}
