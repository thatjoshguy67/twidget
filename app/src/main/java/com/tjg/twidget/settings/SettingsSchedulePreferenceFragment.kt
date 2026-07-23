package com.tjg.twidget.settings

import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.BufferClient
import com.tjg.twidget.schedule.BufferOAuth
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

        if (!connected) {
            screen.addPreference(Preference(context).apply {
                key = "schedule_connect_buffer"
                title = getString(R.string.login_to_buffer)
                setOnPreferenceClickListener {
                    if (!BufferOAuth.isConfigured(context)) {
                        Toast.makeText(context, R.string.schedule_buffer_oauth_unavailable, Toast.LENGTH_LONG).show()
                    } else {
                        runCatching { startActivity(BufferOAuth.authorizationIntent(context)) }
                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    }
                    true
                }
            })
        } else {
            screen.addPreference(category(R.string.accounts))
            val trackedAccounts = TwidgetStore.accounts(context)
                .ifEmpty { listOf(TwidgetStore.settings(context).username) }
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
            trackedAccounts.forEach { tracked ->
                screen.addPreference(Preference(context).apply {
                    key = "schedule_mapping_${tracked.lowercase()}"
                    title = getString(R.string.account_handle, tracked.trimStart('@'))
                    summary = ScheduleSettingsStore.bufferChannelUsernameFor(context, tracked)
                        ?.let { getString(R.string.account_handle, it) }
                        ?: getString(R.string.connected_x_channel)
                    setOnPreferenceClickListener {
                        chooseMapping(tracked)
                        true
                    }
                })
            }

            screen.addPreference(category(R.string.schedule_media_hosting))
            screen.addPreference(Preference(context).apply {
                key = "schedule_cloudinary_cloud_name"
                title = getString(R.string.schedule_cloudinary_cloud_name)
                summary = cloudinarySummary(
                    ScheduleSettingsStore.cloudinaryCloudNameOverride(context),
                    ScheduleSettingsStore.cloudinaryCloudName(context),
                )
                setOnPreferenceClickListener {
                    promptCloudinaryValue(
                        R.string.schedule_cloudinary_cloud_name,
                        ScheduleSettingsStore.cloudinaryCloudNameOverride(context),
                    ) { ScheduleSettingsStore.setCloudinaryCloudName(context, it) }
                    true
                }
            })
            screen.addPreference(Preference(context).apply {
                key = "schedule_cloudinary_upload_preset"
                title = getString(R.string.schedule_cloudinary_upload_preset)
                summary = cloudinarySummary(
                    ScheduleSettingsStore.cloudinaryUploadPresetOverride(context),
                    ScheduleSettingsStore.cloudinaryUploadPreset(context),
                )
                setOnPreferenceClickListener {
                    promptCloudinaryValue(
                        R.string.schedule_cloudinary_upload_preset,
                        ScheduleSettingsStore.cloudinaryUploadPresetOverride(context),
                    ) { ScheduleSettingsStore.setCloudinaryUploadPreset(context, it) }
                    true
                }
            })

            screen.addPreference(category(0))
            screen.addPreference(Preference(context).apply {
                key = "schedule_disconnect_buffer"
                title = SpannableString(getString(R.string.schedule_disconnect_buffer)).apply {
                    setSpan(
                        ForegroundColorSpan(context.getColor(R.color.metric_red)),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                setOnPreferenceClickListener {
                    confirmDisconnect()
                    true
                }
            })
        }

        screen.addBottomInset()
        preferenceScreen = screen
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
                    .setTitle(getString(R.string.accounts))
                    .setItems(labels.toTypedArray()) { _, index ->
                        ScheduleSettingsStore.setBufferChannel(requireContext(), tracked, channels.getOrNull(index - 1))
                        buildScreen()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun cloudinarySummary(override: String?, effective: String?): String = override
        ?: if (effective != null) getString(R.string.schedule_cloudinary_built_in)
        else getString(R.string.schedule_cloudinary_not_set)

    private fun promptCloudinaryValue(titleRes: Int, current: String?, onValue: (String?) -> Unit) {
        val context = requireContext()
        val padding = (24 * resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(current.orEmpty())
            setSelection(text.length)
            setPadding(padding, padding / 3, padding, padding / 3)
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onValue(input.text.toString())
                buildScreen()
            }
            .show()
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

    private fun toast(message: Int) = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
}
