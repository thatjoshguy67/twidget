package com.tjg.twidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

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

        screen.addPreference(category(R.string.source_fxtwitter))
        val fxTwitterStatus = Preference(context).apply {
            key = "fxtwitter_status_pref"
            title = getString(R.string.fxtwitter_status)
            summary = getString(R.string.status_checking)
            isSelectable = false
        }
        screen.addPreference(fxTwitterStatus)
        screen.addPreference(Preference(context).apply {
            key = "fxtwitter_explainer_pref"
            title = getString(R.string.fxtwitter_explainer)
            isSelectable = false
        })

        screen.addPreference(category(R.string.source_self_hosted))
        val bridgeStatus = Preference(context).apply {
            key = "bridge_status_pref"
            title = getString(R.string.bridge_status)
            summary = getString(R.string.status_checking)
            isSelectable = false
        }
        screen.addPreference(bridgeStatus)
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
        val xApiStatus = Preference(context).apply {
            key = "x_api_status_pref"
            title = getString(R.string.x_api_status)
            summary = getString(R.string.status_checking)
            isSelectable = false
        }
        screen.addPreference(xApiStatus)
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
        refreshConnectorStatuses(bridgeStatus, xApiStatus, fxTwitterStatus)
    }

    private fun refreshConnectorStatuses(
        bridgeStatus: Preference,
        xApiStatus: Preference,
        fxTwitterStatus: Preference,
    ) {
        val snapshot = settings
        val appContext = requireContext().applicationContext
        val account = TwidgetStore.accounts(appContext).firstOrNull()
            ?: snapshot.username.takeIf { it.isNotBlank() }
        Thread {
            val bridge = runCatching { bridgeStatusSummary(snapshot) }
                .getOrElse { getString(R.string.status_failed, friendlyError(it)) }
            val xApi = runCatching { xApiStatusSummary(appContext, snapshot, account) }
                .getOrElse { getString(R.string.status_failed, friendlyError(it)) }
            val fxTwitter = runCatching { fxTwitterStatusSummary(account) }
                .getOrElse { getString(R.string.status_failed, friendlyError(it)) }
            if (!isAdded) return@Thread
            requireActivity().runOnUiThread {
                bridgeStatus.summary = connectorStatusPrefix(
                    active = snapshot.dataSource == TwidgetStore.DATA_SOURCE_DEFAULT ||
                        snapshot.dataSource == TwidgetStore.DATA_SOURCE_SELF_HOSTED,
                    fallback = true,
                    status = bridge,
                )
                xApiStatus.summary = connectorStatusPrefix(
                    active = snapshot.dataSource == TwidgetStore.DATA_SOURCE_X_API,
                    fallback = false,
                    status = xApi,
                )
                fxTwitterStatus.summary = connectorStatusPrefix(
                    active = snapshot.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER,
                    fallback = false,
                    status = fxTwitter,
                )
            }
        }.start()
    }

    private fun bridgeStatusSummary(settings: TwidgetSettings): String {
        val baseUrl = settings.bridgeUrl.trim().trimEnd('/').ifBlank { return getString(R.string.status_not_configured) }
        val connection = URL("$baseUrl/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        if (settings.apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).use { reader -> reader.readText() } }.orEmpty()
        if (code !in 200..299) return getString(R.string.status_http_error, code)
        val json = runCatching { JSONObject(body) }.getOrNull()
        val authMode = json?.optString("authMode").orEmpty()
        return if (json?.optBoolean("ok") == true) {
            if (authMode.isBlank()) getString(R.string.status_connected) else getString(R.string.status_connected_detail, authMode)
        } else {
            getString(R.string.status_unexpected_response)
        }
    }

    private fun xApiStatusSummary(context: android.content.Context, settings: TwidgetSettings, account: String?): String {
        if (!XApiClient.hasCredentials(settings)) return getString(R.string.status_not_configured)
        if (account.isNullOrBlank()) return getString(R.string.status_not_configured)
        XApiClient.fetchProfile(context, account)
        return getString(R.string.status_connected)
    }

    // Reports the live follower count so the FxTwitter numbers can be eyeballed
    // against the bridge while evaluating the source.
    private fun fxTwitterStatusSummary(account: String?): String {
        if (account.isNullOrBlank()) return getString(R.string.status_not_configured)
        val profile = FxTwitterClient.fetchProfile(account)
        return getString(
            R.string.status_connected_detail,
            String.format(Locale.getDefault(), "%,d followers", profile.followersCount),
        )
    }

    private fun connectorStatusPrefix(active: Boolean, fallback: Boolean, status: String): String {
        val prefix = when {
            active -> getString(R.string.status_active)
            fallback -> getString(R.string.status_fallback)
            else -> getString(R.string.status_inactive)
        }
        return "$prefix • $status"
    }

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("client-not-enrolled", ignoreCase = true) ||
                message.contains("client forbidden", ignoreCase = true) ->
                getString(R.string.status_x_client_forbidden)
            message.contains("HTTP 403") -> getString(R.string.status_http_error, 403)
            message.contains("HTTP 401") -> getString(R.string.status_http_error, 401)
            else -> message
                .replace(Regex("\\s+"), " ")
                .take(96)
                .ifBlank { error.javaClass.simpleName }
        }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
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
