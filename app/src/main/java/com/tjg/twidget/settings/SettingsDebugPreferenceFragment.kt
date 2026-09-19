package com.tjg.twidget.settings

import com.tjg.twidget.BuildConfig

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.bridge.DebugBridgeLogActivity
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.OnboardingActivity
import com.tjg.twidget.social.SocialOnboardingActivity
import com.tjg.twidget.ui.AppPaletteManager
import com.tjg.twidget.ui.AppPaletteMode
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.widget.TwidgetBriefWidget
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.preference.ColorPickerPreference
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Hidden developer tools, reachable from Settings after tapping the version
 * number in About seven times.
 */
class SettingsDebugPreferenceFragment : InsetPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = TwidgetStore.PREFS
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val hasDummy = hasDummyProfile()

        screen.addPreference(dev.oneuiproject.oneui.preference.SwitchBarPreference(context).apply {
            key = "debug_enabled"
            isPersistent = false
            isChecked = TwidgetStore.debugMenuUnlocked(context)
            setOnPreferenceChangeListener { _, value ->
                TwidgetStore.setDebugMenuUnlocked(context, value as Boolean)
                if (!value) requireActivity().finish()
                true
            }
        })
        screen.addPreference(category(0))

        screen.addPreference(Preference(context).apply {
            key = "debug_rerun_update_onboarding"
            title = getString(R.string.rerun_update_onboarding)
            setOnPreferenceClickListener {
                startActivity(Intent(context, SocialOnboardingActivity::class.java)
                    .putExtra(SocialOnboardingActivity.EXTRA_UPGRADE, true))
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "debug_rerun_onboarding"
            title = getString(R.string.rerun_onboarding)
            setOnPreferenceClickListener {
                startActivity(Intent(context, OnboardingActivity::class.java))
                true
            }
        })
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            isVisible = BuildConfig.IN_APP_UPDATES
            key = "debug_fake_update_pref"
            title = getString(R.string.trigger_fake_update)
            isChecked = TwidgetStore.fakeUpdateAvailable(context)
            setOnPreferenceChangeListener { _, value ->
                TwidgetStore.setFakeUpdateAvailable(context, value as Boolean)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "debug_bridge_log"
            title = getString(R.string.bridge_log)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, DebugBridgeLogActivity::class.java))
                true
            }
        })
        screen.addPreference(category(0))
        screen.addPreference(Preference(context).apply {
            key = "debug_brief_workbench"
            title = getString(R.string.brief_debug_title)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, BriefDebugActivity::class.java))
                true
            }
        })

        val paletteState = AppPaletteManager.debugState(context)
        screen.addPreference(category(R.string.debug_palette_category))
        screen.addPreference(ListPreference(context).apply {
            key = "debug_app_palette_mode"
            isPersistent = false
            title = getString(R.string.debug_palette_mode)
            entries = arrayOf(
                getString(R.string.debug_palette_system),
                getString(R.string.debug_palette_twidget_blue),
                getString(R.string.debug_palette_custom),
            )
            entryValues = AppPaletteMode.entries.map { it.storedValue }.toTypedArray()
            value = paletteState.mode.storedValue
            summary = paletteModeSummary(paletteState.mode, paletteState.supported)
            setOnPreferenceChangeListener { _, value ->
                val mode = AppPaletteMode.fromStored(value as String)
                applyPalette(mode, paletteState.customSeed)
            }
        })
        screen.addPreference(ColorPickerPreference(context).apply {
            key = "debug_app_palette_custom_accent"
            isPersistent = false
            title = getString(R.string.debug_palette_custom_accent)
            summary = AppPaletteManager.colorHex(paletteState.customSeed)
            isEnabled = paletteState.supported && paletteState.mode == AppPaletteMode.CUSTOM
            setAlphaSliderEnabled(false)
            this.value = paletteState.customSeed
            setOnPreferenceChangeListener { pref, value ->
                val color = (value as Int) or 0xFF000000.toInt()
                AppPaletteManager.setCustomSeed(context, color)
                pref.summary = AppPaletteManager.colorHex(color)
                if (AppPaletteManager.mode(context) == AppPaletteMode.CUSTOM) {
                    applyPalette(AppPaletteMode.CUSTOM, color)
                } else {
                    true
                }
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "debug_app_palette_view"
            title = getString(R.string.debug_palette_view)
            summary = paletteViewerSummary(paletteState)
            setOnPreferenceClickListener {
                showPaletteDialog()
                true
            }
        })

        screen.addPreference(category(R.string.dummy_profile))
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "debug_dummy_enabled"
            isPersistent = false
            setTitle(R.string.settings_enable_dummy)
            isChecked = hasDummy
            setOnPreferenceChangeListener { _, value ->
                if (value == true) saveDummyProfile(DEFAULT_DUMMY_FOLLOWERS)
                else {
                    TwidgetStore.removeAccount(context, DUMMY_USERNAME)
                    TwidgetWidget.updateAll(context)
                    TwidgetBriefWidget.updateAll(context)
                }
                listView.post { if (isAdded) buildScreen() }
                true
            }
        })
        if (hasDummy) {
            val followers = TwidgetStore.currentStats(context, DUMMY_USERNAME).followersCount
            screen.addPreference(EditTextPreference(context).apply {
                key = "debug_dummy_followers"
                title = getString(R.string.dummy_follower_count)
                text = followers.toString()
                summary = NumberFormat.getIntegerInstance(Locale.US).format(followers)
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER
                    it.setSelectAllOnFocus(true)
                }
                setOnPreferenceChangeListener { pref, value ->
                    val count = (value as String).toLongOrNull()?.coerceIn(0, 999_999_999) ?: followers
                    saveDummyProfile(count)
                    pref.summary = NumberFormat.getIntegerInstance(Locale.US).format(count)
                    true
                }
            })
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun applyPalette(mode: AppPaletteMode, seed: Int): Boolean {
        val context = requireContext()
        val result = AppPaletteManager.applySelection(context, mode, seed)
        if (!result.success) {
            Toast.makeText(
                context,
                getString(
                    R.string.debug_palette_apply_failed,
                    result.error ?: getString(R.string.debug_palette_unknown_error),
                ),
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        AppPaletteManager.consumePendingWidgetRefresh(context)
        TwidgetWidget.updateAll(context)
        TwidgetBriefWidget.updateAll(context)
        if (result.changed) {
            requireActivity().window.decorView.postDelayed({
                if (isAdded && !requireActivity().isFinishing) requireActivity().recreate()
            }, 200L)
        } else {
            buildScreen()
        }
        return true
    }

    private fun paletteModeSummary(mode: AppPaletteMode, supported: Boolean): String {
        if (!supported && mode != AppPaletteMode.SYSTEM) {
            return getString(R.string.debug_palette_requires_android_14)
        }
        return when (mode) {
            AppPaletteMode.SYSTEM -> getString(R.string.debug_palette_system_summary)
            AppPaletteMode.TWIDGET_BLUE -> getString(R.string.debug_palette_twidget_blue_summary)
            AppPaletteMode.CUSTOM -> getString(R.string.debug_palette_custom_summary)
        }
    }

    private fun paletteViewerSummary(state: com.tjg.twidget.ui.PaletteDebugState): String {
        val mode = when (state.mode) {
            AppPaletteMode.SYSTEM -> getString(R.string.debug_palette_system)
            AppPaletteMode.TWIDGET_BLUE -> getString(R.string.debug_palette_twidget_blue)
            AppPaletteMode.CUSTOM -> getString(R.string.debug_palette_custom)
        }
        val overlay = when {
            !state.supported -> getString(R.string.debug_palette_overlay_unsupported)
            state.customPaletteApplied -> getString(R.string.debug_palette_overlay_registered)
            else -> getString(R.string.debug_palette_overlay_not_registered)
        }
        return "$mode • $overlay"
    }

    private fun showPaletteDialog() {
        val context = requireContext()
        val state = AppPaletteManager.debugState(context)
        val generated = AppPaletteManager.generatedPalette(context)
        val rows = buildList {
            add(paletteViewerSummary(state))
            add(getString(R.string.settings_android_api, android.os.Build.VERSION.SDK_INT))
            state.lastError?.let { add(getString(R.string.settings_last_error, it)) }
            add(getString(R.string.debug_palette_generated))
            listOf(
                R.string.settings_palette_seed to generated.seed,
                R.string.settings_palette_primary_light to generated.primaryLight,
                R.string.settings_palette_primary_dark to generated.primaryDark,
                R.string.settings_palette_control_light to generated.controlLight,
                R.string.settings_palette_control_dark to generated.controlDark,
            ).forEach { (label, color) -> add(getString(R.string.settings_palette_token, getString(label), AppPaletteManager.colorHex(color))) }
            add(getString(R.string.debug_palette_resolved))
            AppPaletteManager.resolvedColors(context).forEach { (label, color) ->
                add(getString(R.string.settings_palette_token, label, AppPaletteManager.colorHex(color)))
            }
            addAll(state.loaderDetails)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.debug_palette_view)
            .setItems(rows.toTypedArray(), null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun hasDummyProfile(): Boolean =
        TwidgetStore.accounts(requireContext()).any { it.equals(DUMMY_USERNAME, ignoreCase = true) }

    // Fake stats plus a week of ramping history so widgets show deltas and the
    // dashboard chart has real-looking data. Syncs against the fake handle
    // fail, so the saved numbers stay put until edited here.
    private fun saveDummyProfile(followers: Long) {
        val context = requireContext()
        TwidgetStore.saveStats(
            context,
            ProfileStats(
                fullName = getString(R.string.settings_demo_name),
                userName = DUMMY_USERNAME,
                followersCount = followers,
                followingsCount = (followers / 12).coerceAtLeast(1),
                statusesCount = (followers / 4).coerceAtLeast(1),
                likeCount = followers * 6,
                isVerified = true,
                isPrivate = false,
                history = dummyHistory(followers),
            ),
        )
        TwidgetWidget.updateAll(context)
    }

    private fun dummyHistory(followers: Long): List<HistorySample> {
        val formatter = SimpleDateFormat("MMM d", Locale.US)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dailyGain = (followers / 200).coerceAtLeast(1)
        return (6 downTo 1).map { daysAgo ->
            val timestamp = today - daysAgo * DAY_MILLIS
            val dayFollowers = (followers - daysAgo * dailyGain).coerceAtLeast(0)
            HistorySample(
                dayLabel = formatter.format(Date(timestamp)),
                followers = dayFollowers,
                following = (dayFollowers / 12).coerceAtLeast(1),
                posts = (dayFollowers / 4).coerceAtLeast(1),
                likes = dayFollowers * 6,
                timestamp = timestamp,
            )
        }
    }

    private fun category(titleRes: Int): PreferenceCategory =
        PreferenceCategory(requireContext()).apply {
            if (titleRes != 0) title = getString(titleRes)
            isIconSpaceReserved = false
        }

    companion object {
        const val DUMMY_USERNAME = "twidgetdemo"
        private const val DEFAULT_DUMMY_FOLLOWERS = 7_671L
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
