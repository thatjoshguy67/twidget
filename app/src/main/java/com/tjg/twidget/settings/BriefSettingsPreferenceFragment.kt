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

class BriefSettingsPreferenceFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val account = TwidgetStore.settings(context).username
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(SwitchBarPreference(context).apply {
            key = "brief_enabled_pref"
            isChecked = BriefSettingsStore.enabled(context)
            setOnPreferenceChangeListener { _, value ->
                BriefSettingsStore.setEnabled(context, value as Boolean)
                true
            }
        })

        screen.addPreference(spacerCategory())
        val preview = layoutInflater.inflate(R.layout.preference_brief_preview, null)
        screen.addPreference(dev.oneuiproject.oneui.preference.LayoutPreference(context, preview).apply {
            key = "brief_preview"
            isSelectable = false
            setAllowDividerAbove(false)
            setAllowDividerBelow(false)
        })
        val intro = layoutInflater.inflate(R.layout.preference_brief_intro, null)
        screen.addPreference(dev.oneuiproject.oneui.preference.LayoutPreference(context, intro).apply {
            key = "brief_intro"
            isSelectable = false
            setAllowDividerAbove(false)
            setAllowDividerBelow(false)
        })

        screen.addPreference(spacerCategory())
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "brief_refresh_on_home"
            title = getString(R.string.brief_refresh_on_home)
            summary = getString(R.string.brief_refresh_on_home_summary)
            isChecked = BriefSettingsStore.refreshOnHome(context)
            setOnPreferenceChangeListener { _, value ->
                BriefSettingsStore.setRefreshOnHome(context, value as Boolean)
                true
            }
        })

        screen.addPreference(spacerCategory())
        screen.addPreference(Preference(context).apply {
            key = "brief_content_pref"
            title = getString(R.string.brief_manage_content)
            setOnPreferenceClickListener {
                requireActivity().startRightSidePopOverActivity(
                    Intent(context, BriefContentSettingsActivity::class.java),
                )
                true
            }
        })

        screen.addPreference(spacerCategory())
        val provider = BriefSettingsStore.provider(context)
        screen.addPreference(SettingsListPreference(context).apply {
            key = "brief_provider_pref"
            title = getString(R.string.brief_provider)
            entries = arrayOf(
                getString(R.string.brief_provider_auto),
                getString(R.string.brief_provider_local),
                "Cloud",
            )
            entryValues = BriefProviderMode.entries.map { it.storageId }.toTypedArray()
            value = provider.storageId
            summary = providerLabel(provider)
            setOnPreferenceChangeListener { preference, newValue ->
                val selected = BriefProviderMode.fromStorageId(newValue as String)
                BriefSettingsStore.setProvider(context, selected)
                BriefStore.resetAi(context, account)
                preference.summary = providerLabel(selected)
                listView.post { buildScreen() }
                true
            }
        })

        screen.addPreference(spacerCategory())
        val cloudService = BriefOpenAi.method(context)
        screen.addPreference(SettingsListPreference(context).apply {
            key = "brief_cloud_service"
            title = "Cloud provider"
            entries = arrayOf("ChatGPT subscription", "OpenAI API", "Gemini API")
            entryValues = arrayOf("chatgpt", "key", "gemini")
            value = cloudService
            summary = entries[entryValues.indexOf(cloudService).coerceAtLeast(0)]
            setOnPreferenceChangeListener { _, selected ->
                BriefOpenAi.prefs(context).edit().putString("method", selected as String).apply()
                BriefStore.resetAi(context, account)
                listView.post { buildScreen() }; true
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
                        listView.post { buildScreen() }
                    }
                    true
                }
            })
        } else if (cloudService == "chatgpt") {
            screen.addPreference(Preference(context).apply {
                title = "Continue with ChatGPT"
                summary = BriefOpenAi.prefs(context).getString("last_sign_in_error", null)?.let { "Last sign-in failed: $it" }
                    ?: BriefOpenAi.active(context)?.optString("email") ?: "Use your ChatGPT plan; account limits apply"
                setOnPreferenceClickListener { startActivity(Intent(context, BriefChatGptActivity::class.java)); true }
            })
            if (BriefOpenAi.active(context) != null) screen.addPreference(Preference(context).apply {
                title = "Use another ChatGPT account"
                setOnPreferenceClickListener { startActivity(Intent(context, BriefChatGptActivity::class.java).putExtra("new_account", true)); true }
            })
            if (BriefOpenAi.active(context)?.optString("refresh_token")?.isNotBlank() == true) screen.addPreference(Preference(context).apply {
                title = "Sign out of ChatGPT"
                setOnPreferenceClickListener {
                    lifecycleScope.launch {
                        val revoked = runInterruptible(Dispatchers.IO) { BriefOpenAi.signOut(context) }
                        BriefStore.resetAi(context, account)
                        if (isAdded) {
                            buildScreen()
                            if (!revoked) AlertDialog.Builder(context).setMessage("Signed out locally. Remote revocation was not confirmed. Disconnect Twidget in ChatGPT Settings to remove its access.").setPositiveButton(android.R.string.ok, null).show()
                        }
                    }; true
                }
            })
            val saved = BriefOpenAi.accounts(context)
            if (saved.length() > 0) screen.addPreference(Preference(context).apply {
                title = "ChatGPT accounts"
                setOnPreferenceClickListener {
                    val labels = (0 until saved.length()).map { i ->
                        val item = saved.getJSONObject(i)
                        item.optString("email") + " · " + item.getString("client_id").takeLast(8)
                    }.toTypedArray()
                    AlertDialog.Builder(context).setTitle("ChatGPT account").setItems(labels) { _, index ->
                        BriefOpenAi.prefs(context).edit().putString("account", saved.getJSONObject(index).getString("client_id")).putString("method", "chatgpt").apply()
                        BriefStore.resetAi(context, account); buildScreen()
                    }.show(); true
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
                            BriefStore.resetAi(context, account); buildScreen()
                        }.show(); true
                }
            })

        }

        if (TwidgetStore.debugMenuUnlocked(context)) {
            screen.addPreference(spacerCategory())
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_pref"
                title = getString(R.string.brief_settings_debug)
                setOnPreferenceClickListener {
                    requireActivity().startSettingsSubActivity(
                        Intent(context, BriefDebugActivity::class.java),
                    )
                    true
                }
            })
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun providerLabel(provider: BriefProviderMode): String = if (provider == BriefProviderMode.CLOUD) "Cloud" else getString(
        when (provider) {
            BriefProviderMode.AUTO -> R.string.brief_provider_auto
            BriefProviderMode.LOCAL -> R.string.brief_provider_local
            BriefProviderMode.CLOUD -> R.string.brief_provider_cloud
        },
    )

    private fun spacerCategory() = PreferenceCategory(requireContext()).apply {
        isIconSpaceReserved = false
    }

}
