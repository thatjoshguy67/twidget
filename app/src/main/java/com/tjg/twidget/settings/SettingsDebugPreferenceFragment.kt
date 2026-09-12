package com.tjg.twidget.settings

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.bridge.DebugBridgeLogActivity
import com.tjg.twidget.data.HistorySample
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.main.OnboardingActivity
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

        screen.addPreference(category(0))

        screen.addPreference(Preference(context).apply {
            key = "debug_rerun_onboarding"
            title = getString(R.string.rerun_onboarding)
            summary = getString(R.string.rerun_onboarding_summary)
            setOnPreferenceClickListener {
                startActivity(Intent(context, OnboardingActivity::class.java))
                true
            }
        })
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "debug_fake_update_pref"
            title = getString(R.string.trigger_fake_update)
            summary = getString(R.string.trigger_fake_update_summary)
            isChecked = TwidgetStore.fakeUpdateAvailable(context)
            setOnPreferenceChangeListener { _, value ->
                TwidgetStore.setFakeUpdateAvailable(context, value as Boolean)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "debug_bridge_log"
            title = getString(R.string.bridge_log)
            summary = getString(R.string.bridge_log_summary)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, DebugBridgeLogActivity::class.java))
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "debug_brief_workbench"
            title = getString(R.string.brief_debug_title)
            summary = getString(R.string.brief_debug_summary)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, BriefDebugActivity::class.java))
                true
            }
        })

        screen.addPreference(category(R.string.dummy_profile))
        if (!hasDummy) {
            screen.addPreference(Preference(context).apply {
                key = "debug_add_dummy"
                title = getString(R.string.add_dummy_profile)
                summary = getString(R.string.add_dummy_profile_summary, DUMMY_USERNAME)
                setOnPreferenceClickListener {
                    saveDummyProfile(DEFAULT_DUMMY_FOLLOWERS)
                    Toast.makeText(context, R.string.dummy_profile_added, Toast.LENGTH_SHORT).show()
                    buildScreen()
                    true
                }
            })
        } else {
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
            screen.addPreference(Preference(context).apply {
                key = "debug_remove_dummy"
                title = SpannableString(getString(R.string.remove_dummy_profile)).apply {
                    setSpan(ForegroundColorSpan(context.getColor(R.color.metric_red)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                setOnPreferenceClickListener {
                    TwidgetStore.removeAccount(context, DUMMY_USERNAME)
                    TwidgetWidget.updateAll(context)
                    Toast.makeText(context, R.string.dummy_profile_removed, Toast.LENGTH_SHORT).show()
                    buildScreen()
                    true
                }
            })
        }

        screen.addPreference(category(0))
        screen.addPreference(Preference(context).apply {
            key = "debug_hide_menu"
            title = getString(R.string.hide_debug_menu)
            setOnPreferenceClickListener {
                TwidgetStore.setDebugMenuUnlocked(context, false)
                requireActivity().finish()
                true
            }
        })

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
            state.overlayRegistered -> getString(R.string.debug_palette_overlay_registered)
            else -> getString(R.string.debug_palette_overlay_not_registered)
        }
        return "$mode • $overlay"
    }

    private fun showPaletteDialog() {
        val context = requireContext()
        val state = AppPaletteManager.debugState(context)
        val generated = AppPaletteManager.generatedPalette(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(12))
            addView(TextView(context).apply {
                setTextColor(context.getColor(R.color.oneui_text_secondary))
                textSize = 13f
                text = buildString {
                    append(paletteViewerSummary(state))
                    append("\nAndroid API ${android.os.Build.VERSION.SDK_INT}")
                    state.overlayDetails.forEach { append("\n$it") }
                    state.lastError?.let { append("\nLast error: $it") }
                }
                setPadding(0, 0, 0, dp(14))
            })
            addView(paletteHeading(getString(R.string.debug_palette_generated)))
            listOf(
                "Seed" to generated.seed,
                "Primary · light" to generated.primaryLight,
                "Primary · dark" to generated.primaryDark,
                "Control · light" to generated.controlLight,
                "Control · dark" to generated.controlDark,
            ).forEach { (label, color) -> addView(paletteRow(label, color)) }
            addView(paletteHeading(getString(R.string.debug_palette_resolved)).apply {
                setPadding(0, dp(18), 0, dp(6))
            })
            AppPaletteManager.resolvedColors(context).forEach { (label, color) ->
                addView(paletteRow(label, color))
            }
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.debug_palette_view)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun paletteHeading(label: String) = TextView(requireContext()).apply {
        text = label
        textSize = 15f
        setTextColor(requireContext().getColor(R.color.oneui_text_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun paletteRow(label: String, color: Int) = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = dp(48)
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(color)
                setStroke(dp(1), context.getColor(R.color.oneui_divider))
            }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
        addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(context.getColor(R.color.oneui_text_primary))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = AppPaletteManager.colorHex(color)
            textSize = 13f
            setTextColor(context.getColor(R.color.oneui_text_secondary))
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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
                fullName = "Twidget Demo",
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
