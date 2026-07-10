package com.tjg.twidget

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
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

        screen.addPreference(Preference(context).apply {
            key = "debug_rerun_onboarding"
            title = getString(R.string.rerun_onboarding)
            summary = getString(R.string.rerun_onboarding_summary)
            setOnPreferenceClickListener {
                startActivity(Intent(context, OnboardingActivity::class.java))
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
