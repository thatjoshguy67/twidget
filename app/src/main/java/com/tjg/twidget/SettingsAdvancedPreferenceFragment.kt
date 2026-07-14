package com.tjg.twidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import org.json.JSONObject
import java.util.Locale

class SettingsAdvancedPreferenceFragment : InsetPreferenceFragment() {
    private lateinit var settings: TwidgetSettings
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusRefreshGeneration = 0

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

    override fun onDestroyView() {
        statusRefreshGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val selfHostedActive = settings.dataSource == TwidgetStore.DATA_SOURCE_SELF_HOSTED
        val xApiActive = settings.dataSource == TwidgetStore.DATA_SOURCE_X_API
        val customUrlSet = settings.bridgeUrl.isNotBlank()

        screen.addPreference(category(R.string.source_fxtwitter))
        val fxTwitterStatus = statusPreference(
            keyName = "fxtwitter_status_pref",
            titleRes = R.string.fxtwitter_status,
            active = settings.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER,
            infoTitleRes = R.string.source_fxtwitter,
            infoTextRes = R.string.fxtwitter_explainer,
        )
        screen.addPreference(fxTwitterStatus)

        screen.addPreference(category(R.string.source_default))
        val bridgeStatus = statusPreference(
            keyName = "bridge_status_pref",
            titleRes = R.string.bridge_status,
            active = settings.dataSource == TwidgetStore.DATA_SOURCE_DEFAULT ||
                (selfHostedActive && !customUrlSet),
            infoTitleRes = R.string.source_default,
            infoTextRes = R.string.bridge_explainer,
        )
        screen.addPreference(bridgeStatus)

        screen.addPreference(category(R.string.source_self_hosted))
        val selfHostedStatus = statusPreference(
            keyName = "self_hosted_status_pref",
            titleRes = R.string.self_hosted_status,
            active = selfHostedActive && customUrlSet,
            infoTitleRes = R.string.source_self_hosted,
            infoTextRes = R.string.self_hosted_explainer,
        )
        screen.addPreference(selfHostedStatus)
        screen.addPreference(EditTextPreference(context).apply {
            key = "self_hosted_url_pref"
            title = getString(R.string.self_hosted_rettiwt)
            text = settings.bridgeUrl
            summary = settings.bridgeUrl.ifBlank { getString(R.string.self_hosted_url_hint) }
            isEnabled = selfHostedActive
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val url = (value as String).trim().trimEnd('/')
                save(settings.copy(bridgeUrl = url))
                pref.summary = url.ifBlank { getString(R.string.self_hosted_url_hint) }
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
        val xApiStatus = statusPreference(
            keyName = "x_api_status_pref",
            titleRes = R.string.x_api_status,
            active = xApiActive,
            infoTitleRes = R.string.source_x_api,
            infoTextRes = R.string.x_api_explainer,
        )
        screen.addPreference(xApiStatus)
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
                    Intent(Intent.ACTION_VIEW, Uri.parse(XApiClient.DEVELOPER_PORTAL_URL))
                )
                true
            }
        })

        screen.addBottomInset()
        preferenceScreen = screen
        refreshConnectorStatuses(bridgeStatus, selfHostedStatus, xApiStatus, fxTwitterStatus)
    }

    private fun refreshConnectorStatuses(
        bridgeStatus: Preference,
        selfHostedStatus: Preference,
        xApiStatus: Preference,
        fxTwitterStatus: Preference,
    ) {
        val snapshot = settings
        val generation = ++statusRefreshGeneration
        val appContext = requireContext().applicationContext
        val account = TwidgetStore.accounts(appContext).firstOrNull()
            ?: snapshot.username.takeIf { it.isNotBlank() }
        val customUrl = snapshot.bridgeUrl.trim().trimEnd('/')
        val rejectedSummary = getString(R.string.status_failed, getString(R.string.sync_failed))
        AppExecutors.execute(onRejected = {
            mainHandler.post {
                if (!isAdded || generation != statusRefreshGeneration) return@post
                bridgeStatus.summary = rejectedSummary
                selfHostedStatus.summary = rejectedSummary
                xApiStatus.summary = rejectedSummary
                fxTwitterStatus.summary = rejectedSummary
            }
        }) {
            val bridge = runCatching { bridgeHealthSummary(TwidgetStore.DEFAULT_BRIDGE_URL, "") }
                .getOrElse { getString(R.string.status_failed, friendlyError(it)) }
            val selfHosted = if (customUrl.isBlank()) {
                getString(R.string.status_not_configured)
            } else {
                runCatching { bridgeHealthSummary(customUrl, snapshot.apiKey) }
                    .getOrElse { getString(R.string.status_failed, friendlyError(it)) }
            }
            val xApi = runCatching { xApiStatusSummary(appContext, snapshot, account) }
                .getOrElse { getString(R.string.status_failed, friendlyError(it)) }
            val fxTwitter = runCatching { fxTwitterStatusSummary(account) }
                .getOrElse { getString(R.string.status_failed, friendlyError(it)) }
            mainHandler.post {
                if (!isAdded || generation != statusRefreshGeneration) return@post
                val selfHostedActive = snapshot.dataSource == TwidgetStore.DATA_SOURCE_SELF_HOSTED &&
                    customUrl.isNotBlank()
                bridgeStatus.summary = connectorStatusPrefix(
                    active = snapshot.dataSource == TwidgetStore.DATA_SOURCE_DEFAULT ||
                        (snapshot.dataSource == TwidgetStore.DATA_SOURCE_SELF_HOSTED && customUrl.isBlank()),
                    // The shared bridge only backs up FxTwitter when the user
                    // has opted into shared history.
                    fallback = snapshot.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER &&
                        snapshot.shareHistory,
                    status = bridge,
                )
                selfHostedStatus.summary = connectorStatusPrefix(
                    active = selfHostedActive,
                    fallback = false,
                    status = selfHosted,
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
        }
    }

    private fun bridgeHealthSummary(baseUrl: String, token: String): String {
        val headers = buildMap {
            if (token.isNotBlank()) put("Authorization", "Bearer $token")
        }
        val response = HttpTransport.get("$baseUrl/health", headers, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
        if (response.code !in 200..299) return getString(R.string.status_http_error, response.code)
        val json = runCatching { JSONObject(response.body) }.getOrNull()
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

    // SESL renders non-selectable rows with the dimmed summary colour, which
    // suits inactive sources; the active source stays selectable so its title
    // keeps the normal colour. Either way the (i) button opens the explainer.
    private fun statusPreference(
        keyName: String,
        titleRes: Int,
        active: Boolean,
        infoTitleRes: Int,
        infoTextRes: Int,
    ): Preference =
        SourceStatusPreference(requireContext()) { showSourceInfo(infoTitleRes, infoTextRes) }.apply {
            key = keyName
            title = getString(titleRes)
            summary = getString(R.string.status_checking)
            isSelectable = active
            setOnPreferenceClickListener {
                showSourceInfo(infoTitleRes, infoTextRes)
                true
            }
        }

    private fun showSourceInfo(titleRes: Int, textRes: Int) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(textRes)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private class SourceStatusPreference(
        context: android.content.Context,
        private val onInfo: () -> Unit,
    ) : Preference(context) {
        init {
            widgetLayoutResource = R.layout.preference_widget_info
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            holder.findViewById(R.id.preference_info_button)?.setOnClickListener { onInfo() }
        }
    }

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
