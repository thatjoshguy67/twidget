package com.tjg.twidget.settings

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import com.tjg.twidget.brief.BriefOpenAi
import com.tjg.twidget.brief.BriefChatGptActivity
import com.tjg.twidget.data.SecureCredentialStore
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.text.InputType
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefApiKeyDialog
import com.tjg.twidget.brief.BriefProviderMode
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.ui.startRightSidePopOverActivity
import dev.oneuiproject.oneui.preference.SwitchBarPreference

internal object CloudAiSettings {
    fun populate(fragment: InsetPreferenceFragment, screen: androidx.preference.PreferenceScreen, onChanged: () -> Unit) = with(fragment) {
        val context = requireContext()
        val account = TwidgetStore.settings(context).username
        val cloudService = BriefOpenAi.method(context)
        screen.addPreference(SettingsListPreference(context).apply {
            key = "brief_cloud_service"
            title = getString(R.string.settings_cloud_ai_provider)
            entries = arrayOf("ChatGPT subscription", "OpenAI API", "Gemini API")
            entryValues = arrayOf("chatgpt", "key", "gemini")
            value = cloudService
            summary = if (cloudService == "chatgpt") BriefOpenAi.active(context)?.optString("email")
                ?.takeIf { it.isNotBlank() }?.let { "ChatGPT · $it" }
                ?: entries[entryValues.indexOf(cloudService).coerceAtLeast(0)]
                else entries[entryValues.indexOf(cloudService).coerceAtLeast(0)]
            setOnPreferenceChangeListener { _, selected ->
                BriefOpenAi.prefs(context).edit().putString("method", selected as String).apply()
                BriefStore.resetAi(context, account)
                listView.post { onChanged() }; true
            }
        })
        if (cloudService == "gemini") {
            screen.addPreference(Preference(context).apply {
                key = "brief_cloud_api_key_pref"
                title = getString(R.string.brief_ai_studio_key)
                val current = BriefSettingsStore.geminiApiKey(context)
                summary = if (current.isBlank()) null else getString(R.string.brief_cloud_key_configured)
                setOnPreferenceClickListener {
                    BriefApiKeyDialog.show(requireActivity(), required = false) { key ->
                        if (BriefSettingsStore.provider(context) != BriefProviderMode.LOCAL) BriefStore.resetAi(context, account)
                        summary = if (key.isBlank()) null else {
                            getString(R.string.brief_cloud_key_configured)
                        }
                        listView.post { onChanged() }
                    }
                    true
                }
            })
        } else if (cloudService == "chatgpt") {
            if (BriefOpenAi.active(context) == null) screen.addPreference(Preference(context).apply {
                key = "brief_chatgpt_sign_in"
                title = "Continue with ChatGPT"
                summary = BriefOpenAi.prefs(context).getString("last_sign_in_error", null)?.let { "Last sign-in failed: $it" }
                    ?: BriefOpenAi.active(context)?.optString("email") ?: "Use your ChatGPT plan; account limits apply"
                setOnPreferenceClickListener { startActivity(Intent(context, BriefChatGptActivity::class.java)); true }
            })
            if (BriefOpenAi.active(context)?.optString("refresh_token")?.isNotBlank() == true) screen.addPreference(Preference(context).apply {
                key = "brief_chatgpt_sign_out"
                title = "Sign out of ChatGPT"
                setOnPreferenceClickListener {
                    lifecycleScope.launch {
                        val revoked = runInterruptible(Dispatchers.IO) { BriefOpenAi.signOut(context) }
                        BriefStore.resetAi(context, account)
                        if (isAdded) {
                            onChanged()
                            if (!revoked) AlertDialog.Builder(context).setMessage("Signed out locally. Remote revocation was not confirmed. Disconnect Twidget in ChatGPT Settings to remove its access.").setPositiveButton(android.R.string.ok, null).show()
                        }
                    }; true
                }
            })
        } else if (cloudService == "key") {
            screen.addPreference(Preference(context).apply {
                title = "OpenAI API key"
                summary = "API usage is billed separately from ChatGPT subscriptions"
                setOnPreferenceClickListener {
                    val input = EditText(context).apply {
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        setText(SecureCredentialStore.read(context, BriefOpenAi.KEY))
                    }
                    AlertDialog.Builder(context).setTitle("OpenAI API key").setView(input)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.save) { _, _ ->
                            SecureCredentialStore.write(context, mapOf(BriefOpenAi.KEY to input.text.toString().trim()))
                            BriefOpenAi.prefs(context).edit().putString("method", "key").apply()
                            BriefStore.resetAi(context, account); onChanged()
                        }.show(); true
                }
            })

        }

    }
}
