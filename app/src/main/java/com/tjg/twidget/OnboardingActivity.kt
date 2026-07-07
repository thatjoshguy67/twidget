package com.tjg.twidget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import dev.oneuiproject.oneui.layout.ToolbarLayout

class OnboardingActivity : AppCompatActivity() {
    private var step = STEP_OVERVIEW
    private var isStarting = false
    private var addAccountMode = false
    private var startedOnWidgetStep = false
    private var previewFloatAnimator: ObjectAnimator? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var heroFollowers = 0L
    private var cycleIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addAccountMode = intent.getBooleanExtra(EXTRA_ADD_ACCOUNT, false)
        startedOnWidgetStep = intent.getBooleanExtra(EXTRA_SHOW_WIDGET_STEP, false)
        if (addAccountMode) step = STEP_PROFILE
        if (startedOnWidgetStep) step = STEP_WIDGETS
        setContentView(R.layout.activity_onboarding)
        setupInput()
        setupButtons()
        renderStep()
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
            STEP_PROFILE -> {
                step = STEP_OVERVIEW
                renderStep()
            }
            STEP_WIDGETS -> {
                if (startedOnWidgetStep) {
                    super.onBackPressed()
                } else {
                    step = STEP_PROFILE
                    renderStep()
                }
            }
            else -> super.onBackPressed()
        }
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
                STEP_OVERVIEW -> {
                    step = STEP_PROFILE
                    renderStep()
                }
                STEP_PROFILE -> submitProfile()
                STEP_WIDGETS -> requestWidgetPin()
            }
        }
        findViewById<AppCompatButton>(R.id.secondary_button).setOnClickListener {
            when (step) {
                STEP_PROFILE -> showAdvancedDialog()
                STEP_WIDGETS -> enterApp()
            }
        }
    }

    private fun renderStep() {
        findViewById<ToolbarLayout>(R.id.onboarding_toolbar_layout).setTitle(
            when (step) {
                STEP_PROFILE -> getString(R.string.profile_username)
                STEP_WIDGETS -> getString(R.string.add_widget)
                else -> getString(R.string.onboarding_title)
            }
        )
        findViewById<View>(R.id.step_overview).visibility =
            if (step == STEP_OVERVIEW) View.VISIBLE else View.GONE
        findViewById<View>(R.id.step_profile).visibility =
            if (step == STEP_PROFILE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.step_widgets).visibility =
            if (step == STEP_WIDGETS) View.VISIBLE else View.GONE
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
        when (step) {
            STEP_OVERVIEW -> startHeroPreview()
            STEP_WIDGETS -> showWidgetPreview()
        }
    }

    private fun updateButtons() {
        val showSecondaryButton = when (step) {
            STEP_PROFILE -> !XApiClient.hasCredentials(TwidgetStore.settings(this))
            STEP_WIDGETS -> true
            else -> false
        }
        val singleButton = !showSecondaryButton
        val density = resources.displayMetrics.density
        findViewById<AppCompatButton>(R.id.primary_button).apply {
            text = when {
                isStarting -> getString(R.string.starting_twidget)
                step == STEP_PROFILE && addAccountMode -> getString(R.string.add_account)
                step == STEP_WIDGETS -> getString(R.string.add_widget)
                else -> getString(R.string.continue_button)
            }
            isEnabled = !isStarting && (step != STEP_PROFILE || cleanUsername().isValidUsername())
            alpha = if (isEnabled) 1f else 0.5f
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = ((if (singleButton) 28 else 5) * density).toInt()
                marginEnd = ((if (singleButton) 28 else 0) * density).toInt()
            }
        }
        findViewById<AppCompatButton>(R.id.secondary_button).apply {
            text = if (step == STEP_WIDGETS) {
                getString(R.string.continue_button)
            } else {
                getString(R.string.advanced_options)
            }
            visibility = if (singleButton) View.GONE else View.VISIBLE
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

    // --- Step 1: live 4x2 widget with a ticking follower count ---

    private fun startHeroPreview() {
        val host = findViewById<FrameLayout>(R.id.hero_preview_host)
        host.background = previewPillBackground()
        if (heroFollowers == 0L) heroFollowers = TwidgetStore.currentStats(this).followersCount
        renderHeroPreview()
        scheduleHeroTick()
    }

    private fun scheduleHeroTick() {
        previewHandler.postDelayed({
            if (step != STEP_OVERVIEW) return@postDelayed
            heroFollowers += listOf(1L, 1L, 2L, 3L, -1L).random()
            renderHeroPreview()
            findViewById<FrameLayout>(R.id.hero_preview_host).apply {
                scaleX = 1.03f
                scaleY = 1.03f
                animate().scaleX(1f).scaleY(1f).setDuration(250).start()
            }
            scheduleHeroTick()
        }, 1600)
    }

    private fun renderHeroPreview() {
        val density = resources.displayMetrics.density
        val stats = TwidgetStore.currentStats(this).copy(followersCount = heroFollowers)
        findViewById<ImageView>(R.id.hero_preview_image).setImageBitmap(
            WidgetArtworkRenderer.render(
                context = this,
                widthPx = (280 * density).toInt(),
                heightPx = (128 * density).toInt(),
                stats = stats,
                settings = TwidgetStore.widgetSettings(this),
                mode = TwidgetWidget.LAYOUT_MODE_LARGE,
                dark = false,
            )
        )
    }

    // --- Step 3: cycle through home widget sizes and lock screen variants ---

    private data class WidgetPreviewSpec(
        val widthDp: Int,
        val heightDp: Int,
        val mode: Int = 0,
        val lockWide: Boolean? = null,
        val pill: Boolean = true,
    )

    private val previewSpecs = listOf(
        WidgetPreviewSpec(280, 128, mode = TwidgetWidget.LAYOUT_MODE_LARGE),
        WidgetPreviewSpec(190, 90, mode = TwidgetWidget.LAYOUT_MODE_COMPACT_2X1),
        WidgetPreviewSpec(142, 156, mode = TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE),
        WidgetPreviewSpec(118, 44, lockWide = true, pill = false),
        WidgetPreviewSpec(52, 56, lockWide = false, pill = false),
    )

    private fun showWidgetPreview() {
        val density = resources.displayMetrics.density
        val host = findViewById<FrameLayout>(R.id.widget_preview_host)
        applyPreviewSpec(previewSpecs[cycleIndex])
        host.alpha = 0f
        host.scaleX = 0.7f
        host.scaleY = 0.7f
        host.translationY = 0f
        host.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(1.1f))
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
        scheduleCycleTick()
    }

    private fun scheduleCycleTick() {
        previewHandler.postDelayed({
            if (step != STEP_WIDGETS) return@postDelayed
            val host = findViewById<FrameLayout>(R.id.widget_preview_host)
            host.animate()
                .alpha(0f)
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(180)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    cycleIndex = (cycleIndex + 1) % previewSpecs.size
                    applyPreviewSpec(previewSpecs[cycleIndex])
                    host.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(280)
                        .setInterpolator(OvershootInterpolator(1.2f))
                        .start()
                }
                .start()
            scheduleCycleTick()
        }, 2600)
    }

    private fun applyPreviewSpec(spec: WidgetPreviewSpec) {
        val density = resources.displayMetrics.density
        val host = findViewById<FrameLayout>(R.id.widget_preview_host)
        host.layoutParams = (host.layoutParams as FrameLayout.LayoutParams).apply {
            width = (spec.widthDp * density).toInt()
            height = (spec.heightDp * density).toInt()
        }
        host.background = if (spec.pill) previewPillBackground() else null
        val bitmap = if (spec.lockWide != null) {
            LockScreenFollowerViews.previewArt(this, spec.lockWide)
        } else {
            WidgetArtworkRenderer.render(
                context = this,
                widthPx = (spec.widthDp * density).toInt(),
                heightPx = (spec.heightDp * density).toInt(),
                stats = TwidgetStore.currentStats(this),
                settings = TwidgetStore.widgetSettings(this),
                mode = spec.mode,
                dark = false,
                delta = TwidgetStore.followersDelta(this).takeIf { it != 0L } ?: 26L,
            )
        }
        findViewById<ImageView>(R.id.widget_preview_image).setImageBitmap(bitmap)
    }

    private fun previewPillBackground() = GradientDrawable().apply {
        cornerRadius = 26f * resources.displayMetrics.density
        setColor(Color.argb(205, 255, 255, 255))
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
        step = STEP_WIDGETS
        renderStep()
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
    }
}
