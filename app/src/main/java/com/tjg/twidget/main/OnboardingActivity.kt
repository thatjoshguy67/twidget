package com.tjg.twidget.main

import com.tjg.twidget.core.UiMessages
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.Manifest
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.NotificationManagerCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersBridgeCache
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.providers.FxTwitterClient
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.widget.LockScreenFollowerViews
import com.tjg.twidget.widget.TwidgetWidget
import com.tjg.twidget.widget.WidgetArtworkRenderer
import com.tjg.twidget.ui.TwidgetHaptics
import com.tjg.twidget.ui.AnimatedTitleView
import dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout

class OnboardingActivity : EdgeToEdgeActivity() {
    private val accountSession by lazy { androidx.lifecycle.ViewModelProvider(this)[OnboardingAccountSession::class.java] }
    private var identityAnimator: ValueAnimator? = null
    private var identityPreDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var identityRevision = 0L
    private var nanoCheck: kotlinx.coroutines.Job? = null
    private var nanoStatus: com.tjg.twidget.brief.BriefLocalStatus? = null
    private var nanoCheckFinished = false
    private val providerSignIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { renderAiReadiness() }
    private var step = STEP_OVERVIEW
    private var isStarting = false
    private var addAccountMode = false
    private var startedOnWidgetStep = false
    private lateinit var onboardingMaterial: OnboardingMaterialDrawable
    private var previewFloatAnimator: ObjectAnimator? = null
    private val previewHandler = Handler(Looper.getMainLooper())
    private val welcomeButtonHandler = Handler(Looper.getMainLooper())
    private var welcomeButtonSpring: SpringAnimation? = null
    private var welcomeButtonFade: ObjectAnimator? = null
    private var primaryButtonBaseElevation = 0f
    private var asyncGeneration = 0L
    private var notificationPermissionRequested = false
    private var permissionSequenceInProgress = false
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionRequested = true
        renderPermissionState()
        updateButtons()
        if (granted) continuePermissionSequence()
    }
    private val stepBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (step) {
                STEP_PERMISSIONS -> goToStep(STEP_OVERVIEW)
                STEP_PROFILE -> goToStep(STEP_PERMISSIONS)
                STEP_CONFIRM -> goToStep(STEP_PROFILE)
                STEP_AI -> goToStep(STEP_CONFIRM)
                STEP_WIDGETS -> goToStep(if (startedOnWidgetStep) STEP_PROFILE else STEP_AI)
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
        step = savedInstanceState?.getInt("onboarding_step", step) ?: step
        if (step == STEP_CONFIRM && accountSession.candidate == null) step = STEP_PROFILE
        setContentView(R.layout.activity_onboarding)
        onboardingMaterial = OnboardingMaterialDrawable(resources,
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        findViewById<View>(R.id.onboarding_root).background = onboardingMaterial
        applyFooterScreenInsets(findViewById(R.id.onboarding_content), findViewById(R.id.onboarding_footer), padRootForIme = false)
        primaryButtonBaseElevation = findViewById<AppCompatButton>(R.id.primary_button).elevation
        findViewById<AdaptiveCoordinatorLayout>(R.id.onboarding_root).configureAdaptiveMargin(
            AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ADP_DEFAULT,
            setOf(findViewById<View>(R.id.onboarding_content)),
        )
        onBackPressedDispatcher.addCallback(this, stepBackCallback)
        setupWelcomeScreen()
        setupInput()
        setupButtons()
        setupAiAndConfirmation()
        setupBackButton()
        setupPermissionRows()
        setupShareHistoryCheckbox()
        renderStep(animate = false)
    }

    private fun setupWelcomeScreen() {
        findViewById<TextView>(R.id.overview_privacy_footer).apply {
            val value = getString(R.string.onboarding_welcome_terms)
            val label = getString(R.string.onboarding_privacy_link)
            val start = value.indexOf(label)
            text = if (start >= 0) {
                android.text.SpannableString(value).apply {
                    setSpan(
                        android.text.style.URLSpan(getString(R.string.link_privacy_policy)),
                        start,
                        start + label.length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else {
                value
            }
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
    }

    override fun onDestroy() {
        cancelIdentityMorph()
        nanoCheck?.cancel()
        onboardingMaterial.dispose()
        asyncGeneration++
        previewHandler.removeCallbacksAndMessages(null)
        previewFloatAnimator?.cancel()
        previewFloatAnimator = null
        cancelWelcomeButtonAnimation()
        super.onDestroy()
    }

    override fun onStop() {
        findViewById<AnimatedTitleView>(R.id.overview_title).cancel()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        onboardingMaterial.resumeMotion()
        if (step == STEP_AI) renderAiReadiness()
        if (step == STEP_PERMISSIONS) {
            renderPermissionState()
            updateButtons()
            if (permissionSequenceInProgress) {
                val state = permissionState()
                when {
                    state.allGranted -> goToStep(STEP_PROFILE)
                }
            }
        }

    }

    override fun onPause() {
        onboardingMaterial.pauseMotion()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("onboarding_step", step)
        super.onSaveInstanceState(outState)
    }

    private fun goToStep(next: Int) {
        if (next != STEP_PERMISSIONS) {
            permissionSequenceInProgress = false
        }
        step = if (next == STEP_CONFIRM && accountSession.candidate == null) STEP_PROFILE else next
        renderStep(animate = true)
    }

    private fun setupInput() {
        findViewById<EditText>(R.id.username_input).apply {
            // Bound raw input without truncating a valid 15-character handle after its @ prefix.
            // The normalized handle is validated separately before any network request.
            filters = arrayOf(InputFilter.LengthFilter(OnboardingHandleInput.MAX_INPUT_LENGTH))
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
                STEP_OVERVIEW -> goToStep(STEP_PERMISSIONS)
                STEP_PERMISSIONS -> if (permissionState().allGranted) {
                    goToStep(STEP_PROFILE)
                } else {
                    permissionSequenceInProgress = true
                    requestNextPermission()
                }
                STEP_PROFILE -> submitProfile()
                STEP_CONFIRM -> confirmProfile()
                STEP_AI -> goToStep(STEP_WIDGETS)
                STEP_WIDGETS -> {
                    requestWidgetPin()
                    goToStep(STEP_DONE)
                }
                STEP_DONE -> enterApp()
            }
        }
        findViewById<AppCompatButton>(R.id.secondary_button).setOnClickListener {
            when (step) {
                STEP_PERMISSIONS -> goToStep(STEP_PROFILE)

                STEP_CONFIRM -> goToStep(STEP_PROFILE)
                STEP_WIDGETS -> goToStep(STEP_DONE)
            }
        }
    }

    private fun setupBackButton() {
        findViewById<ImageButton>(R.id.onboarding_back).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupPermissionRows() {
        findViewById<View>(R.id.permission_notifications_row).setOnClickListener {
            requestNotificationPermission()
        }

    }

    private fun permissionState(): OnboardingPermissionState = OnboardingPermissionState(
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.from(this).areNotificationsEnabled()),
    )

    private fun renderPermissionState() {
        if (step != STEP_PERMISSIONS) return
        val state = permissionState()
        updatePermissionStatus(R.id.permission_notifications_status, state.notificationsGranted)
    }

    private fun updatePermissionStatus(viewId: Int, granted: Boolean) {
        findViewById<TextView>(viewId).apply {
            text = getString(if (granted) R.string.onboarding_permission_allowed else R.string.onboarding_permission_required)
            setTextColor(getColor(R.color.oneui_text_secondary))
        }
    }

    private fun continuePermissionSequence() {
        if (!permissionSequenceInProgress) return
        if (permissionState().allGranted) {
            goToStep(STEP_PROFILE)
        } else {
            requestNextPermission()
        }
    }

    private fun requestNextPermission() {
        when (nextOnboardingPermissionAction(permissionState())) {
            OnboardingPermissionAction.NOTIFICATIONS -> requestNotificationPermission()
            OnboardingPermissionAction.COMPLETE -> continuePermissionSequence()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val runtimeGranted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!runtimeGranted && !notificationPermissionRequested) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    // Saved immediately on toggle: the widgets step has no explicit finish
    // action — users leave via the pin dialog, Continue, or back.
    private fun setupShareHistoryCheckbox() {
        val visible = shouldShowOnboardingHistoryOption(addAccountMode)
        findViewById<View>(R.id.share_history_option).visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
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
        cancelWelcomeButtonAnimation()
        if (step != STEP_PROFILE && step != STEP_CONFIRM) cancelIdentityMorph()
        val steps = mapOf(
            STEP_OVERVIEW to R.id.step_overview,
            STEP_PERMISSIONS to R.id.step_permissions,
            STEP_PROFILE to R.id.step_profile,
            STEP_CONFIRM to R.id.step_profile,
            STEP_AI to R.id.step_ai,
            STEP_WIDGETS to R.id.step_widgets,
            STEP_DONE to R.id.step_done,
        )
        steps.values.distinct().forEach { viewId ->
            findViewById<View>(viewId).visibility = if (steps[step] == viewId) View.VISIBLE else View.GONE
        }
        findViewById<View>(R.id.onboarding_back).visibility =
            if (step == STEP_OVERVIEW) View.GONE else View.VISIBLE
        if (step != STEP_OVERVIEW) onboardingMaterial.show(when (step) {
            STEP_PERMISSIONS -> OnboardingMaterialDrawable.Stage.PERMISSIONS
            STEP_PROFILE -> OnboardingMaterialDrawable.Stage.HANDLE
            STEP_CONFIRM -> OnboardingMaterialDrawable.Stage.CONFIRM
            STEP_AI -> OnboardingMaterialDrawable.Stage.AI
            STEP_WIDGETS -> OnboardingMaterialDrawable.Stage.WIDGET
            else -> OnboardingMaterialDrawable.Stage.DONE
        }, animate)
        updateButtons()
        val imm = getSystemService(InputMethodManager::class.java)
        val input = findViewById<EditText>(R.id.username_input)
        input.isEnabled = step == STEP_PROFILE
        if (step == STEP_PROFILE) {
            input.requestFocus()
            input.post { imm.showSoftInput(input, 0) }
        } else {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        previewHandler.removeCallbacksAndMessages(null)
        previewFloatAnimator?.cancel()
        previewFloatAnimator = null
        val identityMorphing = if (step == STEP_PROFILE || step == STEP_CONFIRM) renderIdentity(animate) else false
        if (step == STEP_AI) { renderAiReadiness(); checkLocalAi() }
        if (step == STEP_WIDGETS) showWidgetPreview()
        if (step == STEP_DONE) showDoneProfileAvatar()
        if (step == STEP_PERMISSIONS) renderPermissionState()
        if (step == STEP_OVERVIEW) startOverviewTitleAnimation()
        else findViewById<AnimatedTitleView>(R.id.overview_title).cancel()
        if (step == STEP_DONE) startDoneTitleAnimation()
        if (animate) {
            if (step == STEP_DONE) {
                TwidgetHaptics.quickRise(findViewById(R.id.onboarding_root))
            }
            if (step == STEP_DONE) {
                animateDoneContentIn()
            } else {
                if (!identityMorphing)
                    steps[step]?.let { animateStepIn(findViewById(it), 18f, 320L) }
                // Footer controls switch state in place; only the page content and material animate.
            }
        }
    }

    private fun startOverviewTitleAnimation() {
        findViewById<AnimatedTitleView>(R.id.overview_title).apply {
            prepare()
            this@OnboardingActivity.findViewById<AppCompatButton>(R.id.primary_button).apply {
                alpha = 0f
                translationY = 0f
                visibility = View.INVISIBLE
            }
            onAnimationFinished = {
                schedulePrimaryButtonAnimation(STEP_OVERVIEW)
            }
            post {
                if (step == STEP_OVERVIEW && !this@OnboardingActivity.isFinishing && !this@OnboardingActivity.isDestroyed &&
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    onboardingMaterial.revealWelcome()
                    play()
                }
            }
        }
    }

    private fun startDoneTitleAnimation() {
        findViewById<OnboardingTitleAnimationView>(R.id.done_title).apply {
            prepare()
            this@OnboardingActivity.findViewById<AppCompatButton>(R.id.primary_button).apply {
                alpha = 0f
                translationY = 0f
                visibility = View.INVISIBLE
            }
            onAnimationFinished = {
                schedulePrimaryButtonAnimation(STEP_DONE)
            }
            post {
                if (step == STEP_DONE && !this@OnboardingActivity.isFinishing && !this@OnboardingActivity.isDestroyed) {
                    play()
                }
            }
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

    private fun animateDoneContentIn() {
        val doneStep = findViewById<View>(R.id.step_done)
        doneStep.animate().cancel()
        doneStep.alpha = 1f
        doneStep.translationY = 0f
        listOf(R.id.done_profile_avatar, R.id.done_summary).forEachIndexed { index, id ->
            findViewById<View>(id).apply {
                animate().cancel()
                alpha = 0f
                translationY = 12f * resources.displayMetrics.density
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(index * 100L)
                    .setDuration(320L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun updateButtons() {
        stepBackCallback.isEnabled = !isStarting && when (step) {
            STEP_CONFIRM -> true
            STEP_AI -> !addAccountMode
            STEP_PERMISSIONS, STEP_PROFILE, STEP_DONE -> !addAccountMode
            STEP_WIDGETS -> !startedOnWidgetStep
            else -> false
        }
        val showSecondary = step == STEP_PERMISSIONS || step == STEP_WIDGETS || step == STEP_CONFIRM
        val footer = findViewById<LinearLayout>(R.id.onboarding_footer)
        val primary = findViewById<AppCompatButton>(R.id.primary_button)
        val primaryOnRight = step == STEP_CONFIRM || step == STEP_PERMISSIONS
        val primaryIndex = if (primaryOnRight) 1 else 0
        if (footer.indexOfChild(primary) != primaryIndex) {
            footer.removeView(primary)
            footer.addView(primary, primaryIndex)
        }
        findViewById<AppCompatButton>(R.id.primary_button).apply {
            text = when {
                isStarting && step == STEP_PROFILE -> getString(R.string.onboarding_checking_account)
                isStarting -> getString(R.string.starting_twidget)
                step == STEP_OVERVIEW -> getString(R.string.get_started)
                step == STEP_PERMISSIONS && !permissionState().allGranted ->
                    getString(R.string.onboarding_allow_permissions)
                step == STEP_PERMISSIONS -> getString(R.string.continue_button)
                step == STEP_CONFIRM -> getString(R.string.onboarding_confirm_yes)
                step == STEP_PROFILE -> getString(R.string.continue_button)
                step == STEP_WIDGETS -> getString(R.string.add_widget)
                else -> getString(R.string.continue_button)
            }
            isEnabled = !isStarting && (step != STEP_PROFILE || cleanUsername().isValidUsername())
            visibility = View.VISIBLE
            alpha = if (isEnabled) 1f else 0.5f
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                if (step == STEP_OVERVIEW) {
                    width = (268 * resources.displayMetrics.density).toInt()
                    weight = 0f
                    marginStart = 0
                    marginEnd = 0
                } else {
                    width = 0
                    weight = 1f
                    marginStart = if (primaryOnRight) dp(6f).toInt() else 0
                    marginEnd = if (showSecondary && !primaryOnRight) {
                        (6 * resources.displayMetrics.density).toInt()
                    } else {
                        0
                    }
                }
            }
            elevation = if (step == STEP_OVERVIEW) dp(1f) else primaryButtonBaseElevation
        }
        findViewById<AppCompatButton>(R.id.secondary_button).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = if (primaryOnRight) 0 else dp(6f).toInt()
                marginEnd = if (primaryOnRight) dp(6f).toInt() else 0
            }
            text = getString(
                if (step == STEP_CONFIRM) R.string.onboarding_confirm_no else R.string.skip,
            )
            visibility = if (showSecondary) View.VISIBLE else View.GONE
            isEnabled = !isStarting
            alpha = if (isEnabled) 1f else 0.5f
        }
    }

    private fun schedulePrimaryButtonAnimation(targetStep: Int) {
        cancelWelcomeButtonAnimation()
        val root = findViewById<View>(R.id.onboarding_root)
        val button = findViewById<AppCompatButton>(R.id.primary_button)
        button.alpha = 0f
        button.translationY = 0f
        welcomeButtonHandler.postDelayed({
            if (step != targetStep || isFinishing || isDestroyed || root.height == 0) return@postDelayed
            val offset = root.height * WELCOME_BUTTON_START_OFFSET
            button.visibility = View.VISIBLE
            button.translationY = offset
            button.alpha = 0f
            welcomeButtonSpring = SpringAnimation(button, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring = SpringForce(0f).apply {
                    stiffness = WELCOME_BUTTON_SPRING_STIFFNESS
                    dampingRatio = WELCOME_BUTTON_SPRING_DAMPING_RATIO
                }
                setStartValue(offset)
                start()
            }
            welcomeButtonFade = ObjectAnimator.ofFloat(button, View.ALPHA, 0f, 1f).apply {
                duration = WELCOME_BUTTON_FADE_DURATION_MS
                interpolator = PathInterpolator(0.17f, 0.17f, 0.83f, 0.83f)
                start()
            }
        }, WELCOME_BUTTON_START_DELAY_MS)
    }

    private fun cancelWelcomeButtonAnimation() {
        welcomeButtonHandler.removeCallbacksAndMessages(null)
        welcomeButtonSpring?.cancel()
        welcomeButtonSpring = null
        welcomeButtonFade?.cancel()
        welcomeButtonFade = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

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
                FxTwitterClient.fetchProfile(username, TwidgetStore.currentStats(this, username).userId).also { profile ->
                    if (!OnboardingHandleInput.sameAccount(profile.userName, username)) {
                        throw HttpTransport.HttpException(404, "FxTwitter returned a different account")
                    }
                }
            }
            postUiIfCurrent(generation) {
                isStarting = false
                updateButtons()
                val error = result.exceptionOrNull()
                when {
                    error == null -> {
                        accountSession.candidate = result.getOrThrow()
                        goToStep(STEP_CONFIRM)
                    }
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

    private fun setupAiAndConfirmation() {
        fun reviewMaterial(button: AppCompatButton) {
            button.setBackgroundResource(R.drawable.dashboard_report_chip)
            button.backgroundTintList = null
            button.supportBackgroundTintList = null
            button.setTextColor(getColor(R.color.oneui_text_primary))
        }
        reviewMaterial(findViewById(R.id.primary_button))
        reviewMaterial(findViewById(R.id.ai_setup_provider))
        listOf(R.id.ai_brief_name, R.id.ai_review_name).forEach {
            com.tjg.twidget.ui.TwidgetFonts.dashboardHeading(findViewById(it), weight = 700)
        }
        listOf(R.id.ai_brief_icon to R.color.oneui_accent,
            R.id.ai_review_icon to R.color.brief_insight_normal_start).forEach { (id, colour) ->
            findViewById<ImageView>(id).apply {
                val tint = getColor(colour)
                imageTintList = android.content.res.ColorStateList.valueOf(tint)
            }
        }
        findViewById<AppCompatButton>(R.id.secondary_button).apply {
            backgroundTintList = null; supportBackgroundTintList = null
            setBackgroundResource(R.drawable.onboarding_glass_button_bg)
            setTextColor(getColor(R.color.oneui_text_primary))
        }
        findViewById<View>(R.id.confirm_import).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.onboarding_ai_confirm_import)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.onboarding_confirm_yes) { _, _ -> confirmProfile(importAnalytics = true) }.show()
        }
        findViewById<View>(R.id.ai_readiness_card).setOnClickListener {
            if (nanoCheck?.isActive != true) {
                nanoCheckFinished = false
                nanoStatus = null
                renderAiReadiness()
                checkLocalAi()
            }
        }
        findViewById<View>(R.id.ai_setup_provider).setOnClickListener {
            com.tjg.twidget.brief.BriefProviderSetupDialog.show(this,
                onChatGptSignIn = { providerSignIn.launch(com.tjg.twidget.brief.BriefChatGptActivity.forSetup(this)) },
                onConfigured = { renderAiReadiness() })
        }
    }

    private fun cancelIdentityMorph() {
        identityRevision++
        val observer = findViewById<View>(R.id.profile_identity_host)?.viewTreeObserver
        identityPreDraw?.let { if (observer?.isAlive == true) observer.removeOnPreDrawListener(it) }
        identityPreDraw = null
        identityAnimator?.removeAllListeners()
        identityAnimator?.cancel()
        identityAnimator = null
    }

    private fun renderIdentity(animate: Boolean): Boolean {
        cancelIdentityMorph()
        val revision = identityRevision
        val confirm = step == STEP_CONFIRM
        val host = findViewById<FrameLayout>(R.id.profile_identity_host)
        val input = findViewById<EditText>(R.id.username_input)
        val profile = findViewById<View>(R.id.detected_profile)
        val extras = findViewById<View>(R.id.confirm_import_group)
        val willAnimate = animate && ValueAnimator.areAnimatorsEnabled() &&
            (confirm || host.layoutParams.height > dp(60f).toInt())
        findViewById<TextView>(R.id.profile_title).setText(if (confirm) R.string.onboarding_confirm_title else R.string.onboarding_profile_title)
        findViewById<View>(R.id.profile_subtitle).visibility = if (confirm) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.share_history_option).visibility =
            if (!confirm && shouldShowOnboardingHistoryOption(addAccountMode)) View.VISIBLE else View.GONE
        if (confirm) accountSession.candidate?.let { stats ->
            findViewById<TextView>(R.id.detected_name).apply {
                com.tjg.twidget.ui.TwidgetFonts.dashboardHeading(this, weight = 700)
                text = stats.fullName.ifBlank { stats.userName }
            }
            findViewById<TextView>(R.id.detected_handle).text = "@${OnboardingHandleInput.normalize(stats.userName)}"
            findViewById<View>(R.id.detected_verified).visibility = if (stats.isVerified == true) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.detected_bio).apply {
                text = stats.profileDetails.bio.orEmpty()
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            ProfileImageLoader.loadInto(this, findViewById(R.id.detected_avatar), stats.profileImage)
        }

        input.visibility = View.VISIBLE
        input.isEnabled = !confirm
        profile.visibility = View.VISIBLE
        extras.visibility = if (confirm) View.VISIBLE else View.GONE
        // Wait for the actual viewport width. A layout listener must never call renderIdentity(false):
        // it can run again during the expansion and silently replace the animation with its last frame.
        val pending = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (revision != identityRevision || isFinishing || isDestroyed) {
                    if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
                if (host.width <= 0) return true
                host.viewTreeObserver.removeOnPreDrawListener(this)
                identityPreDraw = null
                val startHeight = host.height.coerceAtLeast(dp(60f).toInt())
                // Measure once, unconstrained by the collapsed host; leave the content at that height.
                // Only the outer card expands, so text cannot reflow on every animation frame.
                profile.measure(View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val profileHeight = profile.measuredHeight.coerceAtLeast(dp(100f).toInt())
                profile.layoutParams = profile.layoutParams.apply { height = profileHeight }
                val endHeight = if (confirm) profileHeight else dp(60f).toInt()
                val profileAlpha = profile.alpha
                val inputAlpha = input.alpha
                val profileY = profile.translationY
                fun frame(t: Float) {
                    if (revision != identityRevision) return
                    val height = (startHeight + (endHeight - startHeight) * t).toInt()
                    if (host.layoutParams.height != height) host.layoutParams = host.layoutParams.apply { this.height = height }
                    val targetAlpha = if (confirm) 1f else 0f
                    val fade = if (confirm) ((t - .2f) / .8f).coerceIn(0f, 1f) else (t * 3f).coerceAtMost(1f)
                    profile.alpha = profileAlpha + (targetAlpha - profileAlpha) * fade
                    profile.translationY = profileY + ((if (confirm) 0f else dp(12f)) - profileY) * t
                    val inputFade = if (confirm) (t * 3f).coerceAtMost(1f) else ((t - .2f) / .8f).coerceIn(0f, 1f)
                    input.alpha = inputAlpha + ((if (confirm) 0f else 1f) - inputAlpha) * inputFade
                    extras.alpha = profile.alpha
                }
                fun finish() {
                    if (revision != identityRevision) return
                    frame(1f)
                    input.visibility = if (confirm) View.INVISIBLE else View.VISIBLE
                    profile.visibility = if (confirm) View.VISIBLE else View.GONE
                    identityAnimator = null
                }
                if (willAnimate) {
                    identityAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 650L
                        interpolator = PathInterpolator(.22f, 0f, .12f, 1f)
                        addUpdateListener { frame(it.animatedValue as Float) }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) { finish() }
                        })
                        frame(0f)
                        start()
                    }
                    return true
                }
                finish()
                return startHeight == endHeight
            }
        }
        identityPreDraw = pending
        host.viewTreeObserver.addOnPreDrawListener(pending)
        host.invalidate()
        return willAnimate
    }

    private fun confirmProfile(importAnalytics: Boolean = false) {
        val profile = accountSession.confirm { profile ->
            val username = OnboardingHandleInput.normalize(profile.userName)
            if (addAccountMode) TwidgetStore.addOnboardingAccount(this, username)
            else TwidgetStore.completeOnboarding(this, username)
            TwidgetStore.saveStats(this, profile)
            AppExecutors.execute {
                runCatching { TopFollowersBridgeCache.fetch(this, username) }.getOrNull()?.let {
                    TopFollowersStore.write(this, username, it)
                }
                TwidgetWidget.updateAll(this)
            }
        } ?: return
        val username = OnboardingHandleInput.normalize(profile.userName)
        if (addAccountMode) {
            if (importAnalytics) startActivity(Intent(this, com.tjg.twidget.analytics.AnalyticsImportActivity::class.java)
                .putExtra(com.tjg.twidget.analytics.AnalyticsImportActivity.EXTRA_USERNAME, username))
            finish()
            return
        }
        goToStep(STEP_AI)
        if (importAnalytics) startActivity(Intent(this, com.tjg.twidget.analytics.AnalyticsImportActivity::class.java)
            .putExtra(com.tjg.twidget.analytics.AnalyticsImportActivity.EXTRA_USERNAME, username))
    }

    private fun checkLocalAi() {
        if (nanoCheck?.isActive == true || nanoCheckFinished) return
        nanoCheck = lifecycleScope.launch {
            val probe = try {
                kotlinx.coroutines.withTimeoutOrNull(8000L) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.tjg.twidget.brief.GeminiNanoBriefProvider.probe(this@OnboardingActivity)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            nanoStatus = probe?.status
            nanoCheckFinished = true
            if (step == STEP_AI) renderAiReadiness()
        }
    }

    private fun renderAiReadiness() {
        val text = when (nanoStatus) {
            com.tjg.twidget.brief.BriefLocalStatus.AVAILABLE -> R.string.onboarding_ai_ready to R.string.onboarding_ai_ready_summary
            com.tjg.twidget.brief.BriefLocalStatus.DOWNLOADABLE -> R.string.onboarding_ai_download to R.string.onboarding_ai_download_summary
            com.tjg.twidget.brief.BriefLocalStatus.DOWNLOADING -> R.string.onboarding_ai_downloading to R.string.onboarding_ai_downloading_summary
            com.tjg.twidget.brief.BriefLocalStatus.UNAVAILABLE -> R.string.onboarding_ai_cloud to R.string.onboarding_ai_cloud_summary
            null -> if (nanoCheckFinished) R.string.onboarding_ai_unknown to R.string.onboarding_ai_unknown_summary
                else R.string.onboarding_ai_checking to R.string.onboarding_ai_checking_summary
        }
        findViewById<TextView>(R.id.ai_readiness_title).setText(text.first)
        findViewById<TextView>(R.id.ai_readiness_summary).setText(text.second)
        findViewById<ImageView>(R.id.ai_readiness_indicator).apply {
            visibility = if (nanoStatus == null) View.GONE else View.VISIBLE
            setImageResource(when (nanoStatus) {
                com.tjg.twidget.brief.BriefLocalStatus.AVAILABLE -> R.drawable.ic_dropdown_check
                com.tjg.twidget.brief.BriefLocalStatus.DOWNLOADABLE,
                com.tjg.twidget.brief.BriefLocalStatus.DOWNLOADING -> R.drawable.ic_settings_download
                else -> R.drawable.ic_oneui_info_outline
            })
            imageTintList = android.content.res.ColorStateList.valueOf(getColor(
                if (nanoStatus == com.tjg.twidget.brief.BriefLocalStatus.AVAILABLE) R.color.oneui_accent else R.color.oneui_text_secondary))
        }
        findViewById<AppCompatButton>(R.id.ai_setup_provider).apply {
            visibility = if (nanoStatus == com.tjg.twidget.brief.BriefLocalStatus.AVAILABLE) View.GONE else View.VISIBLE
            setText(if (com.tjg.twidget.brief.BriefSettingsStore.cloudApiKey(this@OnboardingActivity).isNotBlank())
                R.string.onboarding_ai_cloud_connected else R.string.onboarding_ai_setup_cloud)
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
        OnboardingHandleInput.normalize(findViewById<EditText>(R.id.username_input).text.toString())

    private fun String.isValidUsername(): Boolean =
        OnboardingHandleInput.isValid(this)

    companion object {
        const val EXTRA_ADD_ACCOUNT = "com.tjg.twidget.extra.ADD_ACCOUNT"
        const val EXTRA_SHOW_WIDGET_STEP = "com.tjg.twidget.extra.SHOW_WIDGET_STEP"
        private const val STEP_OVERVIEW = 0
        private const val STEP_PERMISSIONS = 1
        private const val STEP_PROFILE = 2
        private const val STEP_CONFIRM = 3
        private const val STEP_AI = 6
        private const val STEP_WIDGETS = 4
        private const val STEP_DONE = 5
        private const val WELCOME_BUTTON_START_DELAY_MS = 180L
        private const val WELCOME_BUTTON_FADE_DURATION_MS = 667L
        private const val WELCOME_BUTTON_START_OFFSET = 0.08f
        private const val WELCOME_BUTTON_SPRING_STIFFNESS = 100f
        private const val WELCOME_BUTTON_SPRING_DAMPING_RATIO = 0.65f
    }
}

internal fun onboardingAccountIsMissing(error: Throwable?): Boolean =
    error is HttpTransport.HttpException && error.code == 404

internal fun shouldShowOnboardingHistoryOption(addAccountMode: Boolean): Boolean = !addAccountMode

internal data class OnboardingPermissionState(
    val notificationsGranted: Boolean,
) {
    val allGranted: Boolean get() = notificationsGranted
}

internal enum class OnboardingPermissionAction { NOTIFICATIONS, COMPLETE }

internal fun nextOnboardingPermissionAction(state: OnboardingPermissionState): OnboardingPermissionAction = when {
    !state.notificationsGranted -> OnboardingPermissionAction.NOTIFICATIONS
    else -> OnboardingPermissionAction.COMPLETE
}
