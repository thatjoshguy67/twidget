package com.tjg.twidget

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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton

class OnboardingActivity : AppCompatActivity() {
    private var step = STEP_OVERVIEW
    private var isStarting = false
    private var addAccountMode = false
    private var startedOnWidgetStep = false
    private var previewFloatAnimator: ObjectAnimator? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private var asyncGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addAccountMode = intent.getBooleanExtra(EXTRA_ADD_ACCOUNT, false)
        startedOnWidgetStep = intent.getBooleanExtra(EXTRA_SHOW_WIDGET_STEP, false)
        if (addAccountMode) step = STEP_PROFILE
        if (startedOnWidgetStep) step = STEP_WIDGETS
        setContentView(R.layout.activity_onboarding)
        makeStatusBarTransparent()
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

    // Let the gradient run under the status bar. Edge-to-edge draws the root
    // full-window; its fitsSystemWindows keeps content padded clear of the bars,
    // while the transparent bar reveals the gradient behind it.
    private fun makeStatusBarTransparent() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = !dark
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
            if (step == STEP_WIDGETS) goToStep(STEP_DONE)
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
        val showSecondary = step == STEP_WIDGETS
        findViewById<AppCompatButton>(R.id.primary_button).apply {
            text = when {
                isStarting -> getString(R.string.starting_twidget)
                step == STEP_OVERVIEW -> getString(R.string.get_started)
                step == STEP_PROFILE && addAccountMode -> getString(R.string.add_account)
                step == STEP_PROFILE -> getString(R.string.continue_button)
                step == STEP_WIDGETS -> getString(R.string.add_widget)
                else -> getString(R.string.continue_button)
            }
            isEnabled = !isStarting && (step != STEP_PROFILE || cleanUsername().isValidUsername())
            alpha = if (isEnabled) 1f else 0.5f
        }
        findViewById<AppCompatButton>(R.id.secondary_button).apply {
            text = getString(R.string.skip)
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
            findViewById<EditText>(R.id.username_input).error = getString(R.string.onboarding_username_error)
            return
        }
        if (addAccountMode) {
            isStarting = true
            updateButtons()
            TwidgetStore.addOnboardingAccount(this, username)
            val generation = ++asyncGeneration
            AppExecutors.execute(onRejected = {
                isStarting = false
                updateButtons()
                Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
            }) {
                val result = runCatching {
                    TwidgetStore.saveStats(this, RettiwtClient.refresh(this, username))
                    TwidgetWidget.updateAll(this)
                }
                postUiIfCurrent(generation) {
                    if (result.isFailure) {
                        Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                    }
                    finish()
                }
            }
            return
        }
        TwidgetStore.completeOnboarding(this, username)
        val generation = ++asyncGeneration
        AppExecutors.execute(onRejected = {
            Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
        }) {
            val result = runCatching {
                TwidgetStore.saveStats(this, RettiwtClient.refresh(this, username))
                TwidgetWidget.updateAll(this)
            }
            postUiIfCurrent(generation) {
                if (result.isFailure) {
                    Toast.makeText(this, R.string.sync_failed, Toast.LENGTH_SHORT).show()
                } else if (step == STEP_DONE) {
                    showDoneProfileAvatar()
                }
            }
        }
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
        const val EXTRA_ADD_ACCOUNT = "com.tjg.twidget.extra.ADD_ACCOUNT"
        const val EXTRA_SHOW_WIDGET_STEP = "com.tjg.twidget.extra.SHOW_WIDGET_STEP"
        private const val STEP_OVERVIEW = 0
        private const val STEP_PROFILE = 1
        private const val STEP_WIDGETS = 2
        private const val STEP_DONE = 3
    }
}
