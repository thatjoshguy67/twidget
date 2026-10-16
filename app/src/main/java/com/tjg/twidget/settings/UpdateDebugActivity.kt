package com.tjg.twidget.settings

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SeslSwitchPreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.env.BuildVars
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.update.AppVersion
import com.tjg.twidget.update.UpdateCheckWorker
import com.tjg.twidget.update.UpdateDownloadNotificationHelper
import com.tjg.twidget.update.UpdateNotificationHelper
import dev.oneuiproject.oneui.layout.ToolbarLayout

class UpdateDebugActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyPreferenceScreenInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.twidget_update_debug))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, UpdateDebugPreferenceFragment())
                .commit()
        }
    }
}

class UpdateDebugPreferenceFragment : InsetPreferenceFragment() {
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
        val spoofedVersion = TwidgetStore.spoofedAppVersion(context).orEmpty()
        val spoofEnabled = TwidgetStore.spoofedAppVersionEnabled(context)

        screen.addPreference(PreferenceCategory(context).apply {
            title = getString(R.string.debug_version_spoofing)
            isIconSpaceReserved = false
        })
        screen.addPreference(SeslSwitchPreferenceScreen(context).apply {
            isVisible = BuildVars.IN_APP_UPDATES
            layoutResource = androidx.preference.R.layout.sesl_preference_switch_screen
            key = "debug_spoof_app_version_enabled"
            title = getString(R.string.debug_spoof_app_version_enabled)
            summary = getString(R.string.debug_spoof_app_version_enabled_summary)
            isChecked = spoofEnabled
            setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                TwidgetStore.setSpoofedAppVersionEnabled(context, enabled)
                if (spoofedVersion.isNotBlank()) requestCheck()
                listView.post { if (isAdded) buildScreen() }
                true
            }
            setOnPreferenceClickListener {
                showSpoofedVersionDialog()
                true
            }
        })
        screen.addPreference(PreferenceCategory(context).apply {
            title = getString(R.string.debug_update_tools)
            isIconSpaceReserved = false
        })
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            isVisible = BuildVars.IN_APP_UPDATES
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
            isVisible = BuildVars.IN_APP_UPDATES
            key = "debug_update_download_notification"
            title = getString(R.string.debug_test_update_download_notification)
            summary = getString(R.string.debug_test_update_download_notification_summary)
            setOnPreferenceClickListener {
                UpdateDownloadNotificationHelper.runDebugTest(context)
                true
            }
        })
        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun requestCheck() {
        val context = requireContext()
        UpdateNotificationHelper.resetNotificationHistory(context)
        UpdateCheckWorker.checkNow(context)
        Toast.makeText(context, R.string.debug_spoof_app_version_checking, Toast.LENGTH_SHORT).show()
    }

    private fun showSpoofedVersionDialog() {
        val context = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(TwidgetStore.spoofedAppVersion(context).orEmpty())
            setSelection(text.length)
            setSelectAllOnFocus(true)
            setPadding(padding, padding / 3, padding, padding / 3)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.debug_spoof_app_version)
            .setMessage(R.string.debug_spoof_app_version_summary)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val version = input.text.toString().trim()
                if (version.isNotEmpty() && AppVersion.parse(version) == null) {
                    Toast.makeText(context, R.string.debug_spoof_app_version_invalid, Toast.LENGTH_LONG).show()
                } else {
                    TwidgetStore.setSpoofedAppVersion(context, version.ifBlank { null })
                    if (version.isBlank()) TwidgetStore.setSpoofedAppVersionEnabled(context, false)
                    if (TwidgetStore.spoofedAppVersionEnabled(context)) requestCheck()
                    buildScreen()
                }
            }
            .show()
    }
}
