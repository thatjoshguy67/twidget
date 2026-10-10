package com.tjg.twidget.settings

import android.os.Vibrator
import androidx.lifecycle.Lifecycle
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.ui.TwidgetHaptics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tjg.twidget.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HapticsDebugInstrumentedTest {
    @Test fun previewsCanBeInterruptedAndPageCanBeReopened() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = TwidgetHaptics.forceWaveforms(context)
        try {
            ActivityScenario.launch(HapticsDebugActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.supportFragmentManager.executePendingTransactions()
                    val page = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                        as HapticsDebugPreferenceFragment
                    val toggle = page.findPreference<SwitchPreferenceCompat>("haptics_force_waveforms")!!
                    for (enabled in listOf(true, false)) {
                        if (toggle.isChecked != enabled) toggle.performClick()
                        assertEquals(enabled, TwidgetHaptics.forceWaveforms(context))
                        for (id in 1..8) {
                            val primitive = page.findPreference<Preference>("haptics_primitive_$id")!!
                            assertEquals(context.getSystemService(Vibrator::class.java)?.hasVibrator() == true,
                                primitive.isEnabled)
                            if (primitive.isEnabled) primitive.performClick()
                        }
                        page.findPreference<Preference>("haptics_hold")!!.performClick()
                        page.findPreference<Preference>("haptics_stop")!!.performClick()
                        page.findPreference<Preference>("haptics_enter")!!.performClick()
                    }
                    val screen = page.preferenceScreen
                    // Exercise the real click handlers, including unsupported hardware rows.
                    for (index in 0 until screen.preferenceCount) {
                        val preference = screen.getPreference(index)
                        if (preference !is SwitchPreferenceCompat && preference.isEnabled && preference.isSelectable) preference.performClick()
                    }
                    page.findPreference<Preference>("haptics_hold")!!.performClick()
                    page.findPreference<Preference>("haptics_stop")!!.performClick()
                    page.findPreference<Preference>("haptics_hold")!!.performClick()
                }
                // Leaving during a ramp must cancel its delayed pop and allow a clean return.
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    val page = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                        as HapticsDebugPreferenceFragment
                    assertNotNull(page.findPreference<Preference>("haptics_force_waveforms"))
                    page.findPreference<Preference>("haptics_enter")!!.performClick()
                }
            }
        } finally {
            TwidgetHaptics.setForceWaveforms(context, original)
        }
    }
}
