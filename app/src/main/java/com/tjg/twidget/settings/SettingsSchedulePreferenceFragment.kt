package com.tjg.twidget.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.BufferClient
import com.tjg.twidget.schedule.BufferOAuth
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleSettingsStore
import com.tjg.twidget.ui.InsetPreferenceFragment

class SettingsSchedulePreferenceFragment : InsetPreferenceFragment() {
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
        val connected = BufferOAuth.isConnected(context)
        val oauthAvailable = BufferOAuth.isConfigured(context)

        screen.addPreference(category(R.string.post_scheduling))
        screen.addPreference(ListPreference(context).apply {
            key = "schedule_default_method_pref"
            title = getString(R.string.schedule_default_method)
            entries = arrayOf(getString(R.string.schedule_provider_local), getString(R.string.schedule_provider_buffer))
            entryValues = arrayOf(ScheduleProvider.LOCAL_REMINDER.name, ScheduleProvider.BUFFER.name)
            val selected = ScheduleSettingsStore.defaultProvider(context)
            value = selected.name
            summary = providerLabel(selected)
            setOnPreferenceChangeListener { preference, value ->
                val provider = ScheduleProvider.valueOf(value as String)
                ScheduleSettingsStore.setDefaultProvider(context, provider)
                preference.summary = providerLabel(provider)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            isSelectable = false
            summary = getString(R.string.schedule_buffer_explainer)
        })

        screen.addPreference(category(R.string.schedule_provider_buffer))
        screen.addPreference(Preference(context).apply {
            key = "schedule_connect_buffer"
            title = getString(if (connected) R.string.schedule_buffer_connected else R.string.schedule_connect_buffer)
            summary = getString(
                when {
                    connected -> R.string.schedule_buffer_connected_summary
                    oauthAvailable -> R.string.schedule_connect_buffer_summary
                    else -> R.string.schedule_buffer_oauth_unavailable
                },
            )
            isEnabled = !connected && oauthAvailable
            setOnPreferenceClickListener {
                runCatching { startActivity(BufferOAuth.authorizationIntent(context)) }
                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "schedule_test_buffer"
            title = getString(R.string.schedule_test_connection)
            summary = getString(if (connected) R.string.schedule_buffer_connected_summary else R.string.schedule_connect_buffer_summary)
            isEnabled = connected
            setOnPreferenceClickListener { testConnection(this); true }
        })

        screen.addPreference(category(R.string.schedule_buffer_account))
        listOf(TwidgetStore.settings(context).username).filter(String::isNotBlank).forEach { tracked ->
            val mapped = ScheduleSettingsStore.bufferChannelFor(context, tracked)
            screen.addPreference(Preference(context).apply {
                key = "schedule_mapping_${tracked.lowercase()}"
                title = "@$tracked"
                summary = if (mapped == null) getString(R.string.schedule_mapping_unset, tracked)
                else getString(R.string.schedule_buffer_mapping_selected)
                isEnabled = connected
                setOnPreferenceClickListener { chooseMapping(tracked); true }
            })
        }

        screen.addPreference(category(R.string.schedule_notifications_status))
        screen.addPreference(Preference(context).apply {
            key = "schedule_permissions"
            title = getString(R.string.schedule_notifications_status)
            summary = getString(if (permissionsReady()) R.string.schedule_permissions_ready else R.string.schedule_permissions_attention)
            setOnPreferenceClickListener { reviewPermissions(); true }
        })

        if (connected) {
            screen.addPreference(category(0))
            screen.addPreference(Preference(context).apply {
                key = "schedule_disconnect_buffer"
                title = getString(R.string.schedule_disconnect_buffer)
                summary = getString(R.string.schedule_disconnect_buffer_summary)
                setOnPreferenceClickListener { confirmDisconnect(); true }
            })
        }
        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun testConnection(preference: Preference) {
        preference.isEnabled = false
        preference.summary = getString(R.string.schedule_testing_connection)
        AppExecutors.execute(onRejected = { activity?.runOnUiThread { buildScreen() } }) {
            val result = BufferClient(requireContext()).verifyAccount()
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                preference.isEnabled = true
                preference.summary = result.value?.let {
                    getString(R.string.schedule_connection_success, it.name ?: it.email)
                } ?: getString(
                    R.string.schedule_connection_failed,
                    result.errors.firstOrNull()?.message ?: getString(R.string.schedule_unknown_error),
                )
            }
        }
    }

    private fun chooseMapping(tracked: String) {
        AppExecutors.execute(onRejected = { activity?.runOnUiThread { toast(R.string.schedule_busy) } }) {
            val result = BufferClient(requireContext()).listTwitterChannels()
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val channels = result.value.orEmpty()
                if (channels.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        result.errors.firstOrNull()?.message ?: getString(R.string.schedule_library_empty),
                        Toast.LENGTH_LONG,
                    ).show()
                    return@runOnUiThread
                }
                val labels = listOf(getString(R.string.schedule_mapping_clear)) + channels.map {
                    it.displayName?.takeIf(String::isNotBlank) ?: it.name
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.schedule_buffer_account))
                    .setItems(labels.toTypedArray()) { _, index ->
                        ScheduleSettingsStore.setBufferChannel(requireContext(), tracked, channels.getOrNull(index - 1)?.id)
                        buildScreen()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun reviewPermissions() {
        val context = requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !(context.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        ) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }

    private fun permissionsReady(): Boolean {
        val context = requireContext()
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val alarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(android.content.Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        return notifications && alarms
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.schedule_disconnect_buffer)
            .setMessage(R.string.schedule_disconnect_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.schedule_disconnect_buffer) { _, _ ->
                ScheduleSettingsStore.clearBuffer(requireContext())
                buildScreen()
            }
            .show()
    }

    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        if (title != 0) this.title = getString(title)
        isIconSpaceReserved = false
    }

    private fun providerLabel(provider: ScheduleProvider): String = getString(
        if (provider == ScheduleProvider.BUFFER) R.string.schedule_provider_buffer else R.string.schedule_provider_local
    )

    private fun toast(message: Int) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 7401
    }
}
