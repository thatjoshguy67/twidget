package com.tjg.twidget.main

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.providers.FxTwitterClient
import com.tjg.twidget.schedule.BufferChannel
import com.tjg.twidget.schedule.BufferClient
import com.tjg.twidget.schedule.BufferOAuth
import com.tjg.twidget.schedule.BufferScheduleSync
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleSettingsStore
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.widget.LockScreenFollowerViews
import com.tjg.twidget.widget.TwidgetWidget
import com.tjg.twidget.widget.WidgetArtworkRenderer
import dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout

class OnboardingActivity : EdgeToEdgeActivity() {
    private var step = STEP_OVERVIEW
    private var isStarting = false
    private var addAccountMode = false
    private var startedOnWidgetStep = false
    private var previewFloatAnimator: ObjectAnimator? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var asyncGeneration = 0L
    private var bufferConnectStarted = false
    private val stepBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (step) {
                STEP_PROFILE -> goToStep(STEP_OVERVIEW)
                STEP_SCHEDULING -> goToStep(STEP_PROFILE)
                STEP_WIDGETS -> goToStep(STEP_SCHEDULING)
                STEP_DONE -> goToStep(STEP_WIDGETS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addAccountMode = intent.getBooleanExtra(EXTRA_ADD_ACCOUNT, false)
        startedOnWidgetStep = intent.getBooleanExtra(EXTRA_SHOW_WIDGET_STEP, false)
        if (addAccountMode) step = STEP_PROFILE
        if (startedOnWidgetStep) step = STEP_WIDGETS
        setContentView(R.layout.activity_onboarding)
        findViewById<AdaptiveCoordinatorLayout>(R.id.onboarding_root).configureAdaptiveMargin(
            AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ADP_DEFAULT,
            setOf(findViewById<View>(R.id.onboarding_content)),
        )
        onBackPressedDispatcher.addCallback(this, stepBackCallback)
        setupInput()
        setupButtons()
        setupShareHistoryCheckbox()
        renderStep(animate = false)
    }

    override fun onDestroy() {
        asyncGeneration++
        previewHandler.removeCallbacksAndMessages(null)
        previewFloatAnimator?.cancel()
        previewFloatAnimator = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (bufferConnectStarted && BufferOAuth.isConnected(this)) {
            bufferConnectStarted = false
            configureBufferChannel()
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
                    hideUsernameError()
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
                STEP_SCHEDULING -> connectBuffer()
                STEP_WIDGETS -> {
                    requestWidgetPin()
                    goToStep(STEP_DONE)
                }
                STEP_DONE -> enterApp()
            }
        }
        findViewById<AppCompatButton>(R.id.secondary_button).setOnClickListener {
            when (step) {
                STEP_SCHEDULING -> {
                    ScheduleSettingsStore.setDefaultProvider(this, ScheduleProvider.LOCAL_REMINDER)
                    goToStep(STEP_WIDGETS)
                }
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
            STEP_SCHEDULING to R.id.step_scheduling,
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
        if (step == STEP_DONE) showDoneProfileAvatar()
        settleBackground(animate)
        if (animate) {
            // The "all set" title rises further and slower, settling into the
            // vertical centre; every other step gets the quiet lift.
            val rise = if (step == STEP_DONE) 48f else 18f
            val duration = if (step == STEP_DONE) 640L else 320L
            steps[step]?.let { animateStepIn(findViewById(it), rise, duration) }
            animateButtonsIn()
        }
    }

    // On the final step the blue remains at the top while the near-white
    // (light) / near-black (dark) background settles into the bottom.
    private fun settleBackground(animate: Boolean) {
        val overlay = findViewById<View>(R.id.done_fade_overlay)
        if (step == STEP_DONE) {
            overlay.visibility = View.VISIBLE
            if (animate) {
                overlay.alpha = 0f
                overlay.animate().alpha(1f).setStartDelay(150).setDuration(1100)
                    .setInterpolator(DecelerateInterpolator()).start()
            } else {
                overlay.alpha = 1f
            }
        } else {
            overlay.animate().cancel()
            overlay.alpha = 0f
            overlay.visibility = View.GONE
        }
    }

    // A quiet fade-and-rise as each step's content and buttons appear.
    private fun animateStepIn(view: View, riseDp: Float, duration: Long) {
        view.alpha = 0f
        view.translationY = riseDp * resources.displayMetrics.density
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
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
        stepBackCallback.isEnabled = !isStarting && !addAccountMode && when (step) {
            STEP_PROFILE, STEP_SCHEDULING, STEP_DONE -> true
            STEP_WIDGETS -> !startedOnWidgetStep
            else -> false
        }
        val showSecondary = step == STEP_SCHEDULING || step == STEP_WIDGETS
        findViewById<AppCompatButton>(R.id.primary_button).apply {
            text = when {
                isStarting && step == STEP_PROFILE -> getString(R.string.onboarding_checking_account)
                isStarting -> getString(R.string.starting_twidget)
                step == STEP_OVERVIEW -> getString(R.string.get_started)
                step == STEP_PROFILE && addAccountMode -> getString(R.string.add_account)
                step == STEP_PROFILE -> getString(R.string.continue_button)
                step == STEP_SCHEDULING -> getString(R.string.onboarding_connect_buffer)
                step == STEP_WIDGETS -> getString(R.string.add_widget)
                else -> getString(R.string.continue_button)
            }
            isEnabled = !isStarting && (step != STEP_PROFILE || cleanUsername().isValidUsername())
            alpha = if (isEnabled) 1f else 0.5f
        }
        findViewById<AppCompatButton>(R.id.secondary_button).apply {
            text = getString(
                if (step == STEP_SCHEDULING) R.string.onboarding_run_locally else R.string.skip,
            )
            visibility = if (showSecondary) View.VISIBLE else View.GONE
            isEnabled = !isStarting
            alpha = if (isEnabled) 1f else 0.5f
        }
    }

    // --- Add-widget step: cycle through widget sizes on a translucent card. ---

    private data class WidgetPreviewSpec(
        val widthDp: Int,
        val heightDp: Int,
        val mode: Int = 0,
        val lockWide: Boolean? = null,
        val card: Boolean = true,
    )

    private val previewSpecs = listOf(
        WidgetPreviewSpec(280, 130, mode = TwidgetWidget.LAYOUT_MODE_LARGE),
        WidgetPreviewSpec(142, 156, mode = TwidgetWidget.LAYOUT_MODE_COMPACT_SQUARE),
        WidgetPreviewSpec(190, 90, mode = TwidgetWidget.LAYOUT_MODE_COMPACT_2X1),
    )
    private var previewIndex = 0

    private fun showWidgetPreview() {
        previewIndex = 0
        applyPreviewSpec(previewSpecs[previewIndex])
        val host = findViewById<FrameLayout>(R.id.widget_preview_host)
        host.alpha = 0f
        host.scaleX = 0.85f
        host.scaleY = 0.85f
        host.translationY = 0f
        host.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { startPreviewFloat(host) }
            .start()
        scheduleCyclePreview()
    }

    private fun startPreviewFloat(host: FrameLayout) {
        val density = resources.displayMetrics.density
        previewFloatAnimator = ObjectAnimator.ofFloat(host, View.TRANSLATION_Y, 0f, -6f * density).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // Fade the current size out, swap to the next spec, fade back in.
    private fun scheduleCyclePreview() {
        previewHandler.postDelayed({
            if (step != STEP_WIDGETS) return@postDelayed
            val host = findViewById<FrameLayout>(R.id.widget_preview_host)
            host.animate()
                .alpha(0f).scaleX(0.9f).scaleY(0.9f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    previewIndex = (previewIndex + 1) % previewSpecs.size
                    applyPreviewSpec(previewSpecs[previewIndex])
                    host.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(280)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
            scheduleCyclePreview()
        }, 2400)
    }

    private fun applyPreviewSpec(spec: WidgetPreviewSpec) {
        val density = resources.displayMetrics.density
        val host = findViewById<FrameLayout>(R.id.widget_preview_host)
        host.layoutParams = (host.layoutParams as FrameLayout.LayoutParams).apply {
            width = (spec.widthDp * density).toInt()
            height = (spec.heightDp * density).toInt()
        }
        host.setBackgroundResource(if (spec.card) R.drawable.onboarding_widget_card_bg else 0)
        host.clipToOutline = spec.card
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
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
                dark = dark,
                delta = TwidgetStore.followersDelta(this),
            )
        }
        findViewById<ImageView>(R.id.widget_preview_image).setImageBitmap(bitmap)
    }

    private fun submitProfile() {
        val username = cleanUsername()
        if (!username.isValidUsername()) {
            showUsernameError(R.string.onboarding_username_error)
            return
        }
        hideUsernameError()
        isStarting = true
        updateButtons()
        val generation = ++asyncGeneration
        AppExecutors.execute(onRejected = {
            postUiIfCurrent(generation) {
                isStarting = false
                updateButtons()
                Toast.makeText(this, R.string.onboarding_account_check_failed, Toast.LENGTH_LONG).show()
            }
        }) {
            val result = runCatching {
                FxTwitterClient.fetchProfile(username).also { profile ->
                    if (!profile.userName.equals(username, ignoreCase = true)) {
                        throw HttpTransport.HttpException(404, "FxTwitter returned a different account")
                    }
                }
            }
            result.onSuccess { stats ->
                if (addAccountMode) {
                    TwidgetStore.addOnboardingAccount(this, username)
                } else {
                    TwidgetStore.completeOnboarding(this, username)
                }
                TwidgetStore.saveStats(this, stats)
                TwidgetWidget.updateAll(this)
            }
            postUiIfCurrent(generation) {
                isStarting = false
                updateButtons()
                val error = result.exceptionOrNull()
                when {
                    error == null && addAccountMode -> finish()
                    error == null -> goToStep(STEP_SCHEDULING)
                    onboardingAccountIsMissing(error) -> showUsernameError(R.string.onboarding_account_not_found)
                    else -> Toast.makeText(
                        this,
                        R.string.onboarding_account_check_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun showUsernameError(messageRes: Int) {
        findViewById<TextView>(R.id.username_error).apply {
            text = getString(messageRes)
            visibility = View.VISIBLE
        }
    }

    private fun hideUsernameError() {
        findViewById<TextView>(R.id.username_error).visibility = View.GONE
    }

    private fun connectBuffer() {
        if (!BufferOAuth.isConfigured(this)) {
            Toast.makeText(this, R.string.schedule_buffer_oauth_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            bufferConnectStarted = true
            startActivity(BufferOAuth.authorizationIntent(this))
        }.onFailure {
            bufferConnectStarted = false
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun configureBufferChannel() {
        val generation = ++asyncGeneration
        AppExecutors.execute(onRejected = {
            postUiIfCurrent(generation) { Toast.makeText(this, R.string.schedule_busy, Toast.LENGTH_SHORT).show() }
        }) {
            val result = BufferClient(this).listTwitterChannels()
            postUiIfCurrent(generation) {
                val channels = result.value.orEmpty()
                if (channels.isEmpty()) {
                    Toast.makeText(
                        this,
                        result.errors.firstOrNull()?.message ?: getString(R.string.onboarding_buffer_no_x_account),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    chooseBufferChannel(channels)
                }
            }
        }
    }

    private fun chooseBufferChannel(channels: List<BufferChannel>) {
        val tracked = TwidgetStore.settings(this).username
        val matching = channels.firstOrNull {
            it.name.equals(tracked, ignoreCase = true) || it.displayName.equals(tracked, ignoreCase = true)
        }
        if (matching != null || channels.size == 1) {
            finishBufferConnection(matching ?: channels.single())
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_choose_buffer_account)
            .setItems(channels.map { it.displayName ?: it.name }.toTypedArray()) { _, index ->
                finishBufferConnection(channels[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun finishBufferConnection(channel: BufferChannel) {
        val tracked = TwidgetStore.settings(this).username
        ScheduleSettingsStore.setBufferChannel(this, tracked, channel)
        ScheduleSettingsStore.setDefaultProvider(this, ScheduleProvider.BUFFER)
        Toast.makeText(this, getString(R.string.schedule_connection_success, channel.displayName ?: channel.name), Toast.LENGTH_SHORT).show()
        AppExecutors.execute { BufferScheduleSync(this).sync() }
        goToStep(STEP_WIDGETS)
    }

    private fun showDoneProfileAvatar() {
        val username = TwidgetStore.settings(this).username
        val stats = TwidgetStore.currentStats(this, username)
        ProfileImageLoader.loadInto(
            this,
            findViewById<ImageView>(R.id.done_profile_avatar),
            stats.profileImage,
        )
    }

    private fun postUiIfCurrent(generation: Long, action: () -> Unit) {
        runOnUiThread {
            if (generation != asyncGeneration || isFinishing || isDestroyed) return@runOnUiThread
            action()
        }
    }

    private fun requestWidgetPin() {
        val appWidgetManager = getSystemService(AppWidgetManager::class.java)
        if (!appWidgetManager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, R.string.add_widget_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        appWidgetManager.requestPinAppWidget(
            ComponentName(this, com.tjg.twidget.TwidgetWidget::class.java),
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
        const val EXTRA_ADD_ACCOUNT = "com.tjg.twidget.extra.ADD_ACCOUNT"
        const val EXTRA_SHOW_WIDGET_STEP = "com.tjg.twidget.extra.SHOW_WIDGET_STEP"
        private const val STEP_OVERVIEW = 0
        private const val STEP_PROFILE = 1
        private const val STEP_SCHEDULING = 2
        private const val STEP_WIDGETS = 3
        private const val STEP_DONE = 4
    }
}

internal fun onboardingAccountIsMissing(error: Throwable?): Boolean =
    error is HttpTransport.HttpException && error.code == 404
