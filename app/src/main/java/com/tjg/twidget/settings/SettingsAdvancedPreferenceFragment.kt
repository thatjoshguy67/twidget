package com.tjg.twidget.settings

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
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

        screen.addPreference(category(R.string.source_fxtwitter))
        screen.addPreference(sourceStatusPreference(
            keyName = "fxtwitter_status_pref",
            titleRes = R.string.fxtwitter_status,
            active = settings.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER,
            infoTitleRes = R.string.source_fxtwitter,
            infoTextRes = R.string.fxtwitter_explainer,
        ))

        screen.addPreference(category(R.string.source_default))
        screen.addPreference(sourceStatusPreference(
            keyName = "bridge_status_pref",
            titleRes = R.string.bridge_status,
            active = settings.dataSource == TwidgetStore.DATA_SOURCE_DEFAULT,
            infoTitleRes = R.string.source_default,
            infoTextRes = R.string.bridge_explainer,
            fallback = settings.dataSource == TwidgetStore.DATA_SOURCE_FXTWITTER && settings.shareHistory,
        ))

        screen.addPreference(category(R.string.twitterapis_title))
        screen.addPreference(EditTextPreference(context).apply {
            key = "twitterapis_api_key_pref"
            title = getString(R.string.twitterapis_api_key)
            val current = SecureCredentialStore.read(context, SecureCredentialStore.TWITTERAPIS_API_KEY)
            text = current
            summary = maskedToken(current)
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
                preference.summary = maskedToken(apiKey)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "twitterapis_configure_pref"
            title = getString(R.string.configure)
            summary = getString(R.string.twitterapis_explainer)
            widgetLayoutResource = R.layout.preference_widget_open_link
            setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TwitterApisClient.WEBSITE_URL)))
                true
            }
        })

        screen.addPreference(category(R.string.self_hosted_rettiwt_section))
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

        screen.addPreference(descriptiveCategory(R.string.source_x_api, R.string.x_api_explainer_short))
        screen.addPreference(EditTextPreference(context).apply {
            key = "x_api_token_pref"
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
            widgetLayoutResource = R.layout.preference_widget_open_link
            setOnPreferenceClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XApiClient.DEVELOPER_PORTAL_URL)))
                true
            }
        })

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun category(title: Int) = PreferenceCategory(requireContext()).apply {
        this.title = getString(title)
        isIconSpaceReserved = false
    }

    private fun descriptiveCategory(titleRes: Int, descriptionRes: Int): PreferenceCategory {
        val heading = getString(titleRes)
        val description = getString(descriptionRes)
        return object : PreferenceCategory(requireContext()) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                holder.findViewById(android.R.id.title)?.let { view ->
                    (view as TextView).apply {
                        maxLines = Int.MAX_VALUE
                        text = SpannableString("$heading\n$description").apply {
                            setSpan(StyleSpan(Typeface.BOLD), 0, heading.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            setSpan(
                                StyleSpan(Typeface.NORMAL),
                                heading.length + 1,
                                length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            setSpan(
                                ForegroundColorSpan(context.getColor(R.color.oneui_text_primary)),
                                heading.length + 1,
                                length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }
            }
        }.apply {
            title = "$heading\n$description"
            isIconSpaceReserved = false
        }
    }

    private fun maskedToken(value: String): String =
        if (value.isBlank()) getString(R.string.status_not_configured) else "*".repeat(25)

    private fun sourceStatusPreference(
        keyName: String,
        titleRes: Int,
        active: Boolean,
        infoTitleRes: Int,
        infoTextRes: Int,
        fallback: Boolean = false,
    ): Preference = SourceStatusPreference(requireContext()) {
        AlertDialog.Builder(requireContext())
            .setTitle(infoTitleRes)
            .setMessage(infoTextRes)
            .setPositiveButton(R.string.done, null)
            .show()
    }.apply {
        key = keyName
        title = getString(titleRes)
        summary = getString(
            when {
                active -> R.string.status_active
                fallback -> R.string.status_fallback
                else -> R.string.status_inactive
            }
        )
    }

    private fun save(next: TwidgetSettings) {
        settings = next
        TwidgetStore.saveSettings(requireContext(), next)
        TwidgetWidget.updateAll(requireContext())
    }

    companion object {
        private const val X_LOGIN_URL = "https://x.com/i/flow/login"
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
}
