package com.tjg.twidget.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.tjg.twidget.R
import com.tjg.twidget.data.SecureCredentialStore
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.TwitterApisClient
import com.tjg.twidget.providers.XApiClient
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.widget.TwidgetWidget

class SettingsAdvancedPreferenceFragment : InsetPreferenceFragment() {
    private lateinit var settings: TwidgetSettings

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        settings = TwidgetStore.settings(requireContext())
        preferenceManager.sharedPreferencesName = TwidgetStore.PREFS
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        settings = TwidgetStore.settings(requireContext())
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        when (arguments?.getString(SettingsAdvancedActivity.EXTRA_SOURCE)) {
            TwidgetStore.DATA_SOURCE_TWITTERAPIS -> addTwitterApis(screen)
            TwidgetStore.DATA_SOURCE_X_API -> addXApi(screen)
            else -> addSelfHostedBridge(screen)
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun addTwitterApis(screen: androidx.preference.PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(category(R.string.twitterapis_title))
        screen.addPreference(EditTextPreference(context).apply {
            key = "twitterapis_api_key_pref"
            isPersistent = false
            title = getString(R.string.twitterapis_api_key)
            val current = SecureCredentialStore.read(context, SecureCredentialStore.TWITTERAPIS_API_KEY)
            text = current
            summary = twitterApisKeySummary(current)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { preference, value ->
                val apiKey = (value as String).trim()
                SecureCredentialStore.write(
                    context,
                    mapOf(SecureCredentialStore.TWITTERAPIS_API_KEY to apiKey),
                )
                preference.summary = twitterApisKeySummary(apiKey)
                true
            }
        })
        screen.addDescribedPreference(Preference(context).apply {
            key = "twitterapis_configure_pref"
            title = getString(R.string.configure)
            summary = getString(R.string.twitterapis_explainer)
            setIcon(R.drawable.ic_settings_open)
            setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TwitterApisClient.WEBSITE_URL)))
                true
            }
        })

    }

    private fun addSelfHostedBridge(screen: androidx.preference.PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(category(R.string.source_default))
        screen.addPreference(EditTextPreference(context).apply {
            key = "self_hosted_url_pref"
            title = getString(R.string.self_hosted_rettiwt)
            text = settings.bridgeUrl
            summary = settings.bridgeUrl.ifBlank { TwidgetStore.DEFAULT_BRIDGE_URL }
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { preference, value ->
                val url = (value as String).trim().trimEnd('/')
                save(settings.copy(bridgeUrl = url))
                preference.summary = url.ifBlank { TwidgetStore.DEFAULT_BRIDGE_URL }
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "self_hosted_token_pref"
            isPersistent = false
            title = getString(R.string.rettiwt_api_key)
            text = settings.apiKey
            summary = if (settings.apiKey.isBlank()) {
                getString(R.string.rettiwt_api_key_hint)
            } else {
                getString(R.string.set)
            }
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { preference, value ->
                val token = (value as String).trim()
                save(settings.copy(apiKey = token))
                preference.summary = if (token.isBlank()) {
                    getString(R.string.rettiwt_api_key_hint)
                } else {
                    getString(R.string.set)
                }
                true
            }
        })

    }

    private fun addXApi(screen: androidx.preference.PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(androidx.preference.SeslPreferenceCaption(context).apply {
            title = getString(R.string.x_api_explainer_short)
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "x_api_token_pref"
            isPersistent = false
            title = getString(R.string.x_api_token_short)
            text = settings.xApiToken
            summary = maskedToken(settings.xApiToken)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { preference, value ->
                val token = (value as String).trim()
                save(settings.copy(xApiToken = token))
                preference.summary = maskedToken(token)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "x_login_pref"
            title = getString(R.string.login_to_x)
            setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(X_LOGIN_URL)))
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "x_configure_pref"
            title = getString(R.string.configure)
            setIcon(R.drawable.ic_settings_open)
            setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XApiClient.DEVELOPER_PORTAL_URL)))
                true
            }
        })

    }

    private fun twitterApisKeySummary(personalKey: String): String = when {
        personalKey.isNotBlank() -> getString(R.string.twitterapis_personal_key_active, maskedToken(personalKey))
        else -> getString(R.string.twitterapis_no_key_available)
    }

    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        this.title = getString(title)
        isIconSpaceReserved = false
    }

    private fun maskedToken(value: String): String =
        if (value.isBlank()) getString(R.string.status_not_configured) else "*".repeat(25)

    private fun save(next: TwidgetSettings) {
        settings = next
        TwidgetStore.saveSettings(requireContext(), next)
        TwidgetWidget.updateAll(requireContext())
    }

    companion object {
        private const val X_LOGIN_URL = "https://x.com/i/flow/login"
    }

}
