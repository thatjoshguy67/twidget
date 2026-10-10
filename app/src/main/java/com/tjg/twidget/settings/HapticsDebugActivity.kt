package com.tjg.twidget.settings

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.TwidgetHaptics
import dev.oneuiproject.oneui.layout.ToolbarLayout

class HapticsDebugActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.debug_haptics_title))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.preference_fragment_container, HapticsDebugPreferenceFragment()).commit()
    }
}

class HapticsDebugPreferenceFragment : InsetPreferenceFragment() {
    private var cancelPlayback: (() -> Unit)? = null
    private var pendingPop: Runnable? = null
    private var playbackView: View? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "haptics_force_waveforms"
            title = getString(R.string.debug_haptics_force_waveforms)
            summary = getString(R.string.debug_haptics_force_waveforms_summary)
            isPersistent = false
            isChecked = TwidgetHaptics.forceWaveforms(context)
            setOnPreferenceChangeListener { _, value ->
                stopPlayback()
                TwidgetHaptics.setForceWaveforms(context, value as Boolean)
                updatePrimitiveAvailability()
                true
            }
        })
        screen.addPreference(PreferenceCategory(context).apply { title = getString(R.string.debug_haptics_actions) })
        fun action(key: String, titleRes: Int, supported: Boolean = true, play: (View) -> Unit) {
            screen.addPreference(Preference(context).apply {
                this.key = key
                title = getString(titleRes)
                isPersistent = false
                isEnabled = supported
                if (!supported) summary = getString(R.string.debug_haptics_unsupported)
                setOnPreferenceClickListener {
                    stopPlayback()
                    view?.let { target ->
                        playbackView = target
                        play(target)
                    }
                    true
                }
            })
        }
        action("haptics_hold", R.string.debug_haptics_hold) { target ->
            cancelPlayback = TwidgetHaptics.startHoldRamp(target, 350L)
            pendingPop = Runnable {
                cancelPlayback?.invoke()
                cancelPlayback = null
                pendingPop = null
                TwidgetHaptics.editModePop(target, entering = true)
            }.also { target.postDelayed(it, 350L) }
        }
        action("haptics_enter", R.string.debug_haptics_enter) { TwidgetHaptics.editModePop(it, entering = true) }
        action("haptics_exit", R.string.debug_haptics_exit) { TwidgetHaptics.editModePop(it, entering = false) }
        action("haptics_long_press", R.string.debug_haptics_long_press) { TwidgetHaptics.longPress(it) }
        action("haptics_drag_pickup", R.string.debug_haptics_drag_pickup, Build.VERSION.SDK_INT >= 34) { TwidgetHaptics.dragPickup(it) }
        action("haptics_drag_hold", R.string.debug_haptics_drag_hold, Build.VERSION.SDK_INT >= 34) { TwidgetHaptics.dragHold(it) }
        action("haptics_selection", R.string.debug_haptics_selection) { TwidgetHaptics.selection(it) }
        action("haptics_confirm", R.string.debug_haptics_confirm) { TwidgetHaptics.confirm(it) }
        action("haptics_reject", R.string.debug_haptics_reject) { TwidgetHaptics.reject(it) }
        action("haptics_refresh", R.string.debug_haptics_refresh) { TwidgetHaptics.refresh(it) }
        screen.addPreference(PreferenceCategory(context).apply { title = getString(R.string.debug_haptics_primitives) })
        // Numeric IDs allow older platforms to display the complete list without accessing newer APIs.
        listOf(
            8 to R.string.debug_haptics_low_tick,
            7 to R.string.debug_haptics_tick,
            1 to R.string.debug_haptics_click,
            4 to R.string.debug_haptics_slow_rise,
            5 to R.string.debug_haptics_quick_rise,
            6 to R.string.debug_haptics_quick_fall,
            2 to R.string.debug_haptics_thud,
            3 to R.string.debug_haptics_spin,
        ).forEach { (primitive, title) ->
            val target = requireActivity().findViewById<View>(R.id.preference_fragment_container)
            action("haptics_primitive_$primitive", title, TwidgetHaptics.canPreviewPrimitive(target, primitive)) {
                cancelPlayback = TwidgetHaptics.previewPrimitive(it, primitive)
            }
        }
        action("haptics_stop", R.string.debug_haptics_stop) { }
        screen.addBottomInset()
        preferenceScreen = screen
    }

    override fun onResume() {
        super.onResume()
        updatePrimitiveAvailability()
    }

    private fun updatePrimitiveAvailability() {
        val target = requireActivity().findViewById<View>(R.id.preference_fragment_container)
        for (primitive in 1..8) {
            findPreference<Preference>("haptics_primitive_$primitive")?.apply {
                isEnabled = TwidgetHaptics.canPreviewPrimitive(target, primitive)
                summary = if (isEnabled) null else getString(R.string.debug_haptics_unsupported)
            }
        }
    }

    override fun onStop() {
        stopPlayback()
        super.onStop()
    }

    private fun stopPlayback() {
        pendingPop?.let { playbackView?.removeCallbacks(it) }
        pendingPop = null
        cancelPlayback?.invoke()
        cancelPlayback = null
        playbackView = null
    }
}
