package com.tjg.twidget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

class OnboardingActivity : AppCompatActivity() {
    private var step = STEP_OVERVIEW
    private var isStarting = false
    private var addAccountMode = false
    private var startedOnWidgetStep = false
    private var previewFloatAnimator: ObjectAnimator? = null
    private val previewHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addAccountMode = intent.getBooleanExtra(EXTRA_ADD_ACCOUNT, false)
        startedOnWidgetStep = intent.getBooleanExtra(EXTRA_SHOW_WIDGET_STEP, false)
        if (addAccountMode) step = STEP_PROFILE
        if (startedOnWidgetStep) step = STEP_WIDGETS
        setContentView(R.layout.activity_onboarding)
        setupInput()
        setupButtons()
        setupShareHistoryCheckbox()
        renderStep(animate = false)
    }

    override fun onDestroy() {
        previewHandler.removeCallbacksAndMessages(null)
        previewFloatAnimator?.cancel()
        previewFloatAnimator = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isStarting || addAccountMode) {
            super.onBackPressed()
            return
        }
        when (step) {
            STEP_PROFILE -> goToStep(STEP_OVERVIEW)
            STEP_WIDGETS -> if (startedOnWidgetStep) super.onBackPressed() else goToStep(STEP_PROFILE)
            STEP_DONE -> goToStep(STEP_WIDGETS)
            else -> super.onBackPressed()
        }
    }

    private fun goToStep(next: Int) {
        step = next
        renderStep(animate = true)
    }

    private fun setupInput() {
        findViewById<EditText>(R.id.username_input).apply {
            filters = arrayOf(InputFilter.LengthFilter(15))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitProfile()
                    true
                } else {
                    false
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateButtons()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }

    private fun setupButtons() {
        findViewById<AppCompatButton>(R.id.primary_button).setOnClickListener {
            when (step) {
                STEP_OVERVIEW -> goToStep(STEP_PROFILE)
                STEP_PROFILE -> submitProfile()
                STEP_WIDGETS -> {
                    requestWidgetPin()
                    goToStep(STEP_DONE)
                }
                STEP_DONE -> enterApp()
            }
        }
        findViewById<AppCompatButton>(R.id.secondary_button).setOnClickListener {
            when (step) {
                STEP_PROFILE -> showAdvancedDialog()
                STEP_WIDGETS -> goToStep(STEP_DONE)
            }
        }
    }

    // Saved immediately on toggle: the widgets step has no explicit finish
    // action — users leave via the pin dialog, Continue, or back.
    private fun setupShareHistoryCheckbox() {
        findViewById<CheckBox>(R.id.share_history_checkbox).apply {
            isChecked = TwidgetStore.settings(this@OnboardingActivity).shareHistory
            setOnCheckedChangeListener { _, isChecked ->
                TwidgetStore.saveSettings(
                    this@OnboardingActivity,
                    TwidgetStore.settings(this@OnboardingActivity).copy(shareHistory = isChecked),
                )
            }
        }
    }

    private fun renderStep(animate: Boolean = false) {
        val steps = mapOf(
            STEP_OVERVIEW to R.id.step_overview,
            STEP_PROFILE to R.id.step_profile,
            STEP_WIDGETS to R.id.step_widgets,
            STEP_DONE to R.id.step_done,
        )
        steps.forEach { (which, viewId) ->
            findViewById<View>(viewId).visibility = if (which == step) View.VISIBLE else View.GONE
        }
        updateButtons()
        val imm = getSystemService(InputMethodManager::class.java)
        val input = findViewById<EditText>(R.id.username_input)
        if (step == STEP_PROFILE) {
            input.requestFocus()
            input.post { imm.showSoftInput(input, 0) }
        } else {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        previewHandler.removeCallbacksAndMessages(null)
        previewFloatAnimator?.cancel()
        previewFloatAnimator = null
        if (step == STEP_WIDGETS) showWidgetPreview()
        if (animate) {
            steps[step]?.let { animateStepIn(findViewById(it)) }
            animateButtonsIn()
        }
    }

    // A quiet fade-and-rise as each step's content and buttons appear.
    private fun animateStepIn(view: View) {
        view.alpha = 0f
        view.translationY = 18f * resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(320)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateButtonsIn() {
        listOf(R.id.primary_button, R.id.secondary_button).forEach { id ->
            findViewById<AppCompatButton>(id).takeIf { it.visibility == View.VISIBLE }?.apply {
                val target = if (isEnabled) 1f else 0.5f
                alpha = 0f
                translationY = 12f * resources.displayMetrics.density
                animate().alpha(target).translationY(0f)
                    .setStartDelay(60).setDuration(280)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun updateButtons() {
        val showSecondary = when (step) {
            STEP_PROFILE -> !addAccountMode && !XApiClient.hasCredentials(TwidgetStore.settings(this))
            STEP_WIDGETS -> true
            else -> false
        }
        findViewById<AppCompatButton>(R.id.primary_button).apply {
            text = when {
                isStarting -> getString(R.string.starting_twidget)
                step == STEP_OVERVIEW -> getString(R.string.get_started)
                step == STEP_PROFILE && addAccountMode -> getString(R.string.add_account)
                step == STEP_PROFILE -> getString(R.string.continue_button)
                step == STEP_WIDGETS -> getString(R.string.add_to_home_screen)
                else -> getString(R.string.continue_button)
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (step == STEP_WIDGETS) R.drawable.ic_home_24 else 0, 0, 0, 0,
            )
            isEnabled = !isStarting && (step != STEP_PROFILE || cleanUsername().isValidUsername())
            alpha = if (isEnabled) 1f else 0.5f
        }
        findViewById<AppCompatButton>(R.id.secondary_button).apply {
            text = if (step == STEP_WIDGETS) getString(R.string.skip) else getString(R.string.advanced_options)
            visibility = if (showSecondary) View.VISIBLE else View.GONE
            isEnabled = !isStarting
            alpha = if (isEnabled) 1f else 0.5f
        }
    }

    private fun showAdvancedDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_onboarding_advanced, null)
        val settings = TwidgetStore.settings(this)
        val keyInput = content.findViewById<EditText>(R.id.x_api_key_input).apply {
            setText(settings.xApiKey)
        }
        val secretInput = content.findViewById<EditText>(R.id.x_api_secret_input).apply {
            setText(settings.xApiSecret)
        }
        content.findViewById<View>(R.id.x_api_portal_link).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(X_DEVELOPER_PORTAL_URL)))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.advanced_options)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.connect_x_api, null)
            .create()
        dialog.setOnShowListener {
            val connectButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            connectButton.setOnClickListener {
                val key = keyInput.text.toString().trim()
                val secret = secretInput.text.toString().trim()
                if (key.isBlank() || secret.isBlank()) {
                    (if (key.isBlank()) keyInput else secretInput).apply {
                        error = getString(R.string.x_api_credentials_error)
                        requestFocus()
                    }
                    return@setOnClickListener
                }
                connectButton.isEnabled = false
                connectButton.text = getString(R.string.connecting)
                // Verified on-device against api.x.com; the credentials never
                // touch the Twidget bridge.
                val testUsername = cleanUsername()
                    .ifBlank { settings.username }
                    .ifBlank { "XDevelopers" }
                Thread {
                    val result = runCatching {
                        val bearer = XApiClient.exchangeBearer(key, secret)
                        XApiClient.fetchProfileWithBearer(testUsername, bearer)
                        bearer
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        result.onSuccess { bearer ->
                            TwidgetStore.saveXApiBearer(this, bearer)
                            TwidgetStore.saveSettings(
                                this,
                                TwidgetStore.settings(this).copy(
                                    xApiKey = key,
                                    xApiSecret = secret,
                                    dataSource = TwidgetStore.DATA_SOURCE_X_API,
                                ),
                            )
                            Toast.makeText(this, R.string.x_api_connected, Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                        }.onFailure {
                            connectButton.isEnabled = true
                            connectButton.text = getString(R.string.connect_x_api)
                            val message = xApiConnectError(it)
                            keyInput.error = message
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            keyInput.requestFocus()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun xApiConnectError(error: Throwable): String {
        val message = error.message.orEmpty()
        return if (
            message.contains("client-not-enrolled", ignoreCase = true) ||
            message.contains("client forbidden", ignoreCase = true)
        ) {
            getString(R.string.status_x_client_forbidden)
        } else {
            getString(R.string.x_api_connect_failed)
        }
    }

    // --- Add-widget step: a single 4x2 preview on a glass card, gently floating.

    private fun showWidgetPreview() {
        val density = resources.displayMetrics.density
        val host = findViewById<FrameLayout>(R.id.widget_preview_host)
        host.setBackgroundResource(R.drawable.widget_preview_glass_bg)
        host.clipToOutline = true
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        findViewById<ImageView>(R.id.widget_preview_image).setImageBitmap(
            WidgetArtworkRenderer.render(
                context = this,
                widthPx = (280 * density).toInt(),
                heightPx = (130 * density).toInt(),
                stats = TwidgetStore.currentStats(this),
                settings = TwidgetStore.widgetSettings(this),
                mode = TwidgetWidget.LAYOUT_MODE_LARGE,
                dark = dark,
                delta = TwidgetStore.followersDelta(this).takeIf { it != 0L } ?: 26L,
            )
        )
        host.alpha = 0f
        host.scaleX = 0.85f
        host.scaleY = 0.85f
        host.translationY = 0f
        host.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                previewFloatAnimator = ObjectAnimator.ofFloat(host, View.TRANSLATION_Y, 0f, -6f * density).apply {
                    duration = 2000
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
            .start()
    }

    private fun submitProfile() {
        val username = cleanUsername()
        if (!username.isValidUsername()) {
            findViewById<EditText>(R.id.username_input).error = getString(R.string.onboarding_username_error)
            return
        }
        if (addAccountMode) {
            isStarting = true
            updateButtons()
            TwidgetStore.addOnboardingAccount(this, username)
            Thread {
                val result = runCatching {
                    TwidgetStore.saveStats(this, RettiwtClient.refresh(this, username))
                    TwidgetWidget.updateAll(this)
                }
                runOnUiThread {
                    if (result.isFailure) {
                        Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }.start()
            return
        }
        TwidgetStore.completeOnboarding(this, username)
        Thread {
            val result = runCatching {
                TwidgetStore.saveStats(this, RettiwtClient.refresh(this, username))
                TwidgetWidget.updateAll(this)
            }
            if (result.isFailure) {
                runOnUiThread {
                    Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
        goToStep(STEP_WIDGETS)
    }

    private fun requestWidgetPin() {
        val appWidgetManager = getSystemService(AppWidgetManager::class.java)
        if (!appWidgetManager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.add_widget_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        appWidgetManager.requestPinAppWidget(
            ComponentName(this, TwidgetWidget::class.java),
            null,
            null
        )
        Toast.makeText(this, R.string.add_widget_requested, Toast.LENGTH_SHORT).show()
    }

    private fun enterApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
    }

    private fun cleanUsername(): String =
        findViewById<EditText>(R.id.username_input).text.toString().trim().trimStart('@')

    private fun String.isValidUsername(): Boolean =
        matches(Regex("^[A-Za-z0-9_]{1,15}$"))

    companion object {
        const val X_DEVELOPER_PORTAL_URL = "https://developer.x.com/en/portal/dashboard"
        const val EXTRA_ADD_ACCOUNT = "com.tjg.twidget.extra.ADD_ACCOUNT"
        const val EXTRA_SHOW_WIDGET_STEP = "com.tjg.twidget.extra.SHOW_WIDGET_STEP"
        private const val STEP_OVERVIEW = 0
        private const val STEP_PROFILE = 1
        private const val STEP_WIDGETS = 2
        private const val STEP_DONE = 3
    }
}
