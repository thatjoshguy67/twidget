package com.tjg.twidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat

class SettingsAdvancedPreferenceFragment : PreferenceFragmentCompat() {
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
        val selfHostedActive = settings.dataSource == TwidgetStore.DATA_SOURCE_SELF_HOSTED
        val xApiActive = settings.dataSource == TwidgetStore.DATA_SOURCE_X_API

        screen.addPreference(category(R.string.source_self_hosted))
        screen.addPreference(EditTextPreference(context).apply {
            key = "self_hosted_url_pref"
            title = getString(R.string.self_hosted_rettiwt)
            text = settings.bridgeUrl
            summary = settings.bridgeUrl.ifBlank { getString(R.string.rettiwt_bridge_hint) }
            isEnabled = selfHostedActive
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val url = (value as String).trim().trimEnd('/')
                save(settings.copy(bridgeUrl = url))
                pref.summary = url.ifBlank { getString(R.string.rettiwt_bridge_hint) }
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "self_hosted_token_pref"
            title = getString(R.string.rettiwt_api_key)
            text = settings.apiKey
            summary = if (settings.apiKey.isBlank()) getString(R.string.rettiwt_api_key_hint) else getString(R.string.set)
            isEnabled = selfHostedActive
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val token = (value as String).trim()
                save(settings.copy(apiKey = token))
                pref.summary = if (token.isBlank()) getString(R.string.rettiwt_api_key_hint) else getString(R.string.set)
                true
            }
        })

        screen.addPreference(category(R.string.source_x_api))
        screen.addPreference(Preference(context).apply {
            key = "x_api_explainer_pref"
            title = getString(R.string.x_api_explainer)
            isSelectable = false
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "x_api_key_pref"
            title = getString(R.string.x_api_key)
            text = settings.xApiKey
            summary = maskedToken(settings.xApiKey)
            isEnabled = xApiActive
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val apiKey = (value as String).trim()
                TwidgetStore.saveXApiBearer(context, "")
                save(settings.copy(xApiKey = apiKey))
                pref.summary = maskedToken(apiKey)
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "x_api_secret_pref"
            title = getString(R.string.x_api_secret)
            text = settings.xApiSecret
            summary = maskedToken(settings.xApiSecret)
            isEnabled = xApiActive
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val apiSecret = (value as String).trim()
                TwidgetStore.saveXApiBearer(context, "")
                save(settings.copy(xApiSecret = apiSecret))
                pref.summary = maskedToken(apiSecret)
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "x_api_token_pref"
            title = getString(R.string.x_api_token)
            text = settings.xApiToken
            summary = maskedToken(settings.xApiToken)
            isEnabled = xApiActive
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val token = (value as String).trim()
                save(settings.copy(xApiToken = token))
                pref.summary = maskedToken(token)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "x_configure_pref"
            title = getString(R.string.configure)
            widgetLayoutResource = R.layout.preference_widget_open_link
            isEnabled = xApiActive
            setOnPreferenceClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(OnboardingActivity.X_DEVELOPER_PORTAL_URL))
                )
                true
            }
        })

        preferenceScreen = screen
    }

    private fun maskedToken(token: String): String =
        if (token.isBlank()) "*".repeat(26) else "*".repeat(token.length.coerceIn(12, 26))

    private fun category(titleRes: Int): PreferenceCategory =
        PreferenceCategory(requireContext()).apply {
            title = getString(titleRes)
            isIconSpaceReserved = false
        }

    private fun save(next: TwidgetSettings) {
        settings = next
        TwidgetStore.saveSettings(requireContext(), next)
        TwidgetWidget.updateAll(requireContext())
    }
}
