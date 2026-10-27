package com.tjg.twidget.brief

import android.view.ViewGroup
import android.animation.ArgbEvaluator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.airbnb.lottie.LottieAnimationView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.ImportedAnalyticsStore
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.data.AccountAverageSeries
import com.tjg.twidget.data.DailyStreakStore
import com.tjg.twidget.data.HistoryRange
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.followers.TopFollowersBrowseActivity
import com.tjg.twidget.followers.TopFollowersStore
import com.tjg.twidget.main.MilestoneArcView
import com.tjg.twidget.main.MilestoneCardBackgroundDrawable
import com.tjg.twidget.main.MilestoneGoalActivity
import com.tjg.twidget.main.MilestoneGoalDialog
import com.tjg.twidget.main.MilestoneGoalStore
import com.tjg.twidget.main.MilestoneMetricResolver
import com.tjg.twidget.main.MilestonePerformanceState
import com.tjg.twidget.main.MilestonePolicy
import com.tjg.twidget.main.StreakCardFactory
import com.tjg.twidget.schedule.BufferScheduleSync
import com.tjg.twidget.schedule.LocalUriMedia
import com.tjg.twidget.schedule.PublicUrlMedia
import com.tjg.twidget.schedule.ScheduleComposeActivity
import com.tjg.twidget.schedule.ScheduleActivity
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleStatus
import com.tjg.twidget.schedule.ScheduleStore
import com.tjg.twidget.schedule.ScheduledPost
import com.tjg.twidget.settings.BriefSettingsActivity
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.CardValueTextView
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startRightSidePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TwidgetBriefActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var renderedSnapshot: BriefSnapshot? = null
    private var visiblePresentation: BriefPresentation? = null
    private var refreshReviewPreviews: (() -> Unit)? = null
    private var localStatus = BriefLocalStatus.UNAVAILABLE
    private var debugScenario: BriefDebugScenario = BriefDebugScenario.REAL
    private var reloadOnResume = false
    private var checkContentOnResume = false
    private var entranceSections: List<View> = emptyList()
    private var backgroundAnimator: ValueAnimator? = null
    private var loadingStatusIndex = 0
    private var loadingStatusRunnable: Runnable? = null
    private val loadingStatusAppearInterpolator = PathInterpolator(0.22f, 0f, 0.18f, 1f)
    private val loadingStatusDisappearInterpolator = PathInterpolator(0.42f, 0f, 1f, 1f)
    private var providerSetupRequired = false
    private var apiKeyPromptShown = false
    private var apiKeyDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_twidget_brief)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
            .ifBlank { TwidgetStore.settings(this).username }
        val defaultAccount = TwidgetStore.settings(this).username.trim().trimStart('@')
        if (username.isBlank() || !username.equals(defaultAccount, ignoreCase = true)) {
            finish()
            return
        }
        if (TwidgetStore.debugMenuUnlocked(this)) {
            debugScenario = BriefDebugScenario.fromStorageId(
                intent.getStringExtra(EXTRA_DEBUG_SCENARIO),
            )
        }
        apiKeyPromptShown = intent.getBooleanExtra(EXTRA_API_KEY_PROMPT_SHOWN, false)
        BriefSettingsStore.setEnabled(this, true)

        bindChrome()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BriefPublishedReviewStore.updates.collect { refreshReviewPreviews?.invoke() }
            }
        }
        showLaunchShell()
        if (intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)) {
            prepareOnboardingBackgroundTransition()
        }
        afterFirstFrame {
            if (savedInstanceState == null) {
                com.tjg.twidget.ui.TwidgetHaptics.quickRise(findViewById(R.id.brief_root))
            }
            loadInitialBrief()
        }
    }

    private fun loadInitialBrief() {
        if (intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false) ||
            intent.getBooleanExtra(EXTRA_WAIT_FOR_LAUNCH_GENERATION, false)
        ) {
            awaitLaunchGeneration()
            return
        }
        val contentChanged = debugScenario == BriefDebugScenario.REAL &&
            BriefSettingsStore.contentRegenerationPending(this)
        val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)
        lifecycleScope.launch {
            val initial = withContext(Dispatchers.IO) {
                val source = BriefEngine.rebuild(this@TwidgetBriefActivity, username)
                Triple(source, BriefStore.presentation(this@TwidgetBriefActivity, username, source),
                    AnalyticsClient.cached(this@TwidgetBriefActivity, username))
            }
            val cached = if (!forceRefresh && debugScenario == BriefDebugScenario.REAL &&
                initial.first.hasGeneratedNarrative && BriefAiCachePolicy.isFresh(initial.first) &&
                BriefRunCache.providerMatches(BriefSettingsStore.provider(this@TwidgetBriefActivity), initial.first.providerUsed))
                initial.first to initial.third else null
            if (cached != null && cached.first.hasGeneratedNarrative) {
                if (contentChanged) BriefSettingsStore.clearContentRegenerationPending(this@TwidgetBriefActivity)
                render(cached.first, cached.second)
                prepareBriefEntrance()
                setLoading(false)
                startPreparedBriefEntrance()
                loadBrief(showSpinner = false, animateOnComplete = false)
            } else {
                if (debugScenario == BriefDebugScenario.REAL) initial.second?.let {
                    showRememberedPresentation(it)
                }
                loadBrief(
                    forceEngine = forceRefresh || contentChanged,
                    forceAi = forceRefresh,
                    showSpinner = true,
                    animateOnComplete = true,
                    clearContentPendingOnSuccess = contentChanged,
                )
            }
        }
    }

    private fun showLaunchShell() {
        findViewById<View>(R.id.brief_scroll).visibility = View.GONE
        findViewById<View>(R.id.brief_loading_container).visibility = View.GONE
        findViewById<LottieAnimationView>(R.id.brief_loading).apply {
            cancelAnimation()
        }
        stopLoadingStatusRotation()
        findViewById<ImageButton>(R.id.brief_footer_info).isEnabled = false
        findViewById<ImageButton>(R.id.brief_footer_settings).isEnabled = false
    }

    private fun afterFirstFrame(block: () -> Unit) {
        val root = findViewById<View>(R.id.brief_root)
        val observer = root.viewTreeObserver
        var scheduled = false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        listener = ViewTreeObserver.OnDrawListener {
            if (!scheduled) {
                scheduled = true
                root.post {
                    if (observer.isAlive) observer.removeOnDrawListener(listener)
                    if (!isFinishing && !isDestroyed) block()
                }
            }
        }
        observer.addOnDrawListener(listener)
    }

    private fun awaitLaunchGeneration() {
        setLoading(true)
        lifecycleScope.launch {
            var entrancePrepared = false
            try {
                val remembered = withContext(Dispatchers.IO) { BriefStore.presentation(this@TwidgetBriefActivity, username) }
                remembered?.let { showRememberedPresentation(it); setLoading(true) }
                val generation = BriefLaunchGeneration.current(username)
                    ?: BriefLaunchGeneration.start(
                        this@TwidgetBriefActivity,
                        username,
                        restartIfComplete = false,
                    )
                val result = runCatching { generation.await() }.getOrElse {
                    val source = withContext(Dispatchers.IO) {
                        BriefEngine.rebuild(this@TwidgetBriefActivity, username, force = true)
                    }
                    BriefAiResult(source, BriefLocalStatus.UNAVAILABLE)
                }
                localStatus = result.localStatus
                if (showProviderSetupIfRequired(result)) return@launch
                render(result.snapshot)
                prepareBriefEntrance()
                entrancePrepared = true
            } finally {
                setLoading(false)
                if (entrancePrepared) startPreparedBriefEntrance()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (reloadOnResume) {
            reloadOnResume = false
            val contentChanged = debugScenario == BriefDebugScenario.REAL &&
                BriefSettingsStore.contentRegenerationPending(this)
            loadBrief(
                forceEngine = true,
                forceAi = false,
                showSpinner = contentChanged,
                animateOnComplete = contentChanged,
                clearContentPendingOnSuccess = contentChanged,
            )
            return
        }
        if (checkContentOnResume) {
            checkContentOnResume = false
            val contentChanged = debugScenario == BriefDebugScenario.REAL &&
                BriefSettingsStore.contentRegenerationPending(this)
            if (contentChanged) {
                loadBrief(
                    forceEngine = true,
                    forceAi = false,
                    showSpinner = true,
                    animateOnComplete = true,
                    clearContentPendingOnSuccess = true,
                )
            } else {
                renderedSnapshot?.let { render(it) }
            }
        }
    }

    private fun loadBrief(
        forceEngine: Boolean = false,
        forceAi: Boolean = false,
        showSpinner: Boolean = renderedSnapshot == null,
        animateOnComplete: Boolean = showSpinner,
        clearContentPendingOnSuccess: Boolean = false,
    ) {
        lifecycleScope.launch {
            var entrancePrepared = false
            if (showSpinner || forceAi) setLoading(true)
            try {
                val source = withContext(Dispatchers.IO) {
                    if (forceAi) {
                        val content = BriefSettingsStore.enabledContent(this@TwidgetBriefActivity)
                        if (content.any(BriefContentCategory::usesScheduledPostData)) {
                            // Refresh Buffer only when an enabled category can use schedule data.
                            runCatching {
                                BufferScheduleSync(this@TwidgetBriefActivity).sync(userInitiated = true)
                            }
                        }
                        BriefStore.resetAi(this@TwidgetBriefActivity, username)
                    }
                    debugScenario.snapshot(
                        this@TwidgetBriefActivity,
                        BriefEngine.rebuild(
                            this@TwidgetBriefActivity,
                            username,
                            force = forceEngine,
                        ),
                    )
                }
                val ready = !forceAi && source.hasGeneratedNarrative && BriefAiCachePolicy.isFresh(source) &&
                    BriefRunCache.providerMatches(BriefSettingsStore.provider(this@TwidgetBriefActivity), source.providerUsed)
                if (debugScenario == BriefDebugScenario.REAL && !ready) {
                    val remembered = withContext(Dispatchers.IO) { BriefStore.presentation(this@TwidgetBriefActivity, username, source) }
                    if (remembered != null) {
                        if (renderedSnapshot == null) showRememberedPresentation(remembered)
                    } else {
                        visiblePresentation = null
                        renderedSnapshot = null
                    }
                }
                if (!ready) setLoading(true)
                val result = if (debugScenario == BriefDebugScenario.REAL && !ready) {
                    BriefAiCoordinator.enrich(
                        this@TwidgetBriefActivity,
                        source,
                        force = forceAi,
                    )
                } else {
                    BriefAiResult(source, if (source.providerUsed == BriefProviderUsed.LOCAL) BriefLocalStatus.AVAILABLE else BriefLocalStatus.UNAVAILABLE)
                }
                localStatus = result.localStatus
                if (showProviderSetupIfRequired(result)) return@launch
                if (result.snapshot != renderedSnapshot) render(result.snapshot)
                if (clearContentPendingOnSuccess) {
                    BriefSettingsStore.clearContentRegenerationPending(this@TwidgetBriefActivity)
                }
                if (animateOnComplete && renderedSnapshot != null) {
                    prepareBriefEntrance()
                    entrancePrepared = true
                }
            } finally {
                setLoading(false)
                if (entrancePrepared) startPreparedBriefEntrance()
            }
        }
    }

    override fun onDestroy() {
        apiKeyDialog?.dismiss()
        apiKeyDialog = null
        backgroundAnimator?.cancel()
        backgroundAnimator = null
        stopLoadingStatusRotation()
        super.onDestroy()
    }

    private fun showRememberedPresentation(presentation: BriefPresentation) {
        visiblePresentation = presentation
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
            .showStaticText(getString(R.string.brief_summary_title_week))
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body).showStaticText(presentation.body)
        findViewById<LinearLayout>(R.id.brief_cards).removeAllViews()
        entranceSections = emptyList()
    }

    private fun setLoading(loading: Boolean) {
        val remembered = visiblePresentation != null
        val showBrief = !loading && (remembered || !providerSetupRequired)
        val scroll = findViewById<View>(R.id.brief_scroll)
        val loadingContainer = findViewById<View>(R.id.brief_loading_container)
        val loadingAnimation = findViewById<LottieAnimationView>(R.id.brief_loading)
        findViewById<View>(R.id.brief_provider_required).visibility =
            if (!remembered && !loading && providerSetupRequired) View.VISIBLE else View.GONE
        if (loading) {
            scroll.animate().cancel()
            scroll.alpha = 1f
            scroll.translationY = 0f
            scroll.visibility = View.GONE
            loadingContainer.animate().cancel()
            loadingContainer.alpha = 1f
            loadingContainer.translationY = 0f
            loadingContainer.visibility = View.VISIBLE
            loadingAnimation.animate().cancel()
            loadingAnimation.alpha = 1f
            if (ValueAnimator.areAnimatorsEnabled()) loadingAnimation.playAnimation() else {
                loadingAnimation.cancelAnimation()
                loadingAnimation.progress = 0.5f
            }
            startLoadingStatusRotation()
        } else if (showBrief && loadingContainer.visibility == View.VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            val offset = 12f * resources.displayMetrics.density
            val loadingStatus = findViewById<TextView>(R.id.brief_loading_status)
            scroll.visibility = View.VISIBLE
            scroll.alpha = 0f
            scroll.translationY = offset
            stopLoadingStatusRotation(resetView = false)
            loadingStatus.animate()
                .alpha(0f)
                .setDuration(300L)
                .setInterpolator(loadingStatusDisappearInterpolator)
                .start()
            loadingAnimation.cancelAnimation()
            loadingAnimation.animate()
                .alpha(0f)
                .setDuration(300L)
                .setInterpolator(loadingStatusDisappearInterpolator)
                .start()
            loadingContainer.animate()
                .alpha(0f)
                .translationY(-offset)
                .setStartDelay(140L)
                .setDuration(320L)
                .setInterpolator(loadingStatusDisappearInterpolator)
                .withEndAction {
                    loadingContainer.visibility = View.GONE
                    loadingContainer.alpha = 1f
                    loadingContainer.translationY = 0f
                    loadingAnimation.alpha = 1f
                }
                .start()
            scroll.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(140L)
                .setDuration(380L)
                .setInterpolator(loadingStatusAppearInterpolator)
                .start()
        } else {
            scroll.visibility = if (showBrief) View.VISIBLE else View.GONE
            loadingContainer.visibility = View.GONE
            loadingAnimation.cancelAnimation()
            stopLoadingStatusRotation()
        }
        findViewById<View>(R.id.brief_refresh_state).visibility =
            if (remembered && !loading && providerSetupRequired) View.VISIBLE else View.GONE
        findViewById<LottieAnimationView>(R.id.brief_refresh_loading).apply {
            visibility = View.GONE
            cancelAnimation()
        }
        findViewById<View>(R.id.brief_refresh_error).visibility =
            if (remembered && !loading && providerSetupRequired) View.VISIBLE else View.GONE
        val contentEnabled = !loading && (remembered || !providerSetupRequired)
        findViewById<ImageButton>(R.id.brief_footer_info).isEnabled = contentEnabled
        findViewById<ImageButton>(R.id.brief_footer_settings).isEnabled = contentEnabled
    }

    private fun startLoadingStatusRotation() {
        if (loadingStatusRunnable != null) return
        val statusView = findViewById<TextView>(R.id.brief_loading_status)
        val offset = 10f * resources.displayMetrics.density
        val messageIds = intArrayOf(
            R.string.brief_loading_status_analyzing,
            R.string.brief_loading_status_insights,
            R.string.brief_loading_status_preparing,
        )
        loadingStatusIndex = 0
        val runnable = object : Runnable {
            override fun run() {
                if (loadingStatusRunnable !== this) return
                val currentRunnable = this
                val message = getString(messageIds[loadingStatusIndex % messageIds.size])
                loadingStatusIndex += 1
                statusView.animate().cancel()
                if (ValueAnimator.areAnimatorsEnabled() && statusView.alpha > 0f) {
                    val fadeOut = ObjectAnimator.ofFloat(statusView, View.ALPHA, statusView.alpha, 0f).apply {
                        duration = 170L
                        interpolator = loadingStatusAppearInterpolator
                    }
                    val moveOut = ObjectAnimator.ofFloat(
                        statusView,
                        View.TRANSLATION_Y,
                        statusView.translationY,
                        -offset,
                    ).apply {
                        duration = 280L
                        interpolator = loadingStatusDisappearInterpolator
                    }
                    AnimatorSet().apply {
                        playTogether(fadeOut, moveOut)
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (loadingStatusRunnable !== currentRunnable) return
                                statusView.text = message
                                statusView.alpha = 0f
                                statusView.translationY = offset
                                statusView.animate()
                                    .alpha(1f)
                                    .translationY(0f)
                                    .setDuration(420L)
                                    .setInterpolator(loadingStatusAppearInterpolator)
                                    .start()
                            }
                        })
                        start()
                    }
                } else {
                    statusView.text = message
                    statusView.alpha = 1f
                    statusView.translationY = 0f
                }
                statusView.postDelayed(this, 2_400L)
            }
        }
        loadingStatusRunnable = runnable
        runnable.run()
    }

    private fun stopLoadingStatusRotation(resetView: Boolean = true) {
        val statusView = findViewById<TextView>(R.id.brief_loading_status)
        loadingStatusRunnable?.let(statusView::removeCallbacks)
        loadingStatusRunnable = null
        statusView.animate().cancel()
        if (resetView) {
            statusView.alpha = 0f
            statusView.translationY = 0f
        }
    }

    private fun bindChrome() {
        val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.brief_scroll)
        applyEdgeToEdgeInsets(findViewById(R.id.brief_root)) { bottom -> BriefPageChrome.navigationInset(scroll, bottom) }
        scroll.seslSetFadingEdgeEnabled(false)
        val tint = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
        BriefPageChrome.compact(findViewById(R.id.brief_root)) { onBackPressedDispatcher.onBackPressed() }
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title).typeface =
            TwidgetFonts.forApp(this, 600)
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body).typeface =
            TwidgetFonts.forApp(this, 400)
        findViewById<TextView>(R.id.brief_footer_account).text = "@$username"
        findViewById<View>(R.id.brief_provider_required_action).setOnClickListener {
            showRequiredApiKeyDialog()
        }
        findViewById<ImageButton>(R.id.brief_footer_info).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_info_outline))
            imageTintList = tint
            setOnClickListener { showProviderInfo() }
        }
        findViewById<ImageButton>(R.id.brief_footer_settings).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_settings_outline))
            imageTintList = tint
            setOnClickListener {
                checkContentOnResume = true
                startRightSidePopOverActivity(
                    Intent(this@TwidgetBriefActivity, BriefSettingsActivity::class.java),
                )
            }
        }
    }

    private fun showProviderSetupIfRequired(result: BriefAiResult): Boolean {
        val required = debugScenario == BriefDebugScenario.REAL && !result.snapshot.hasGeneratedNarrative && briefRequiresApiKey(
            result.localStatus,
            BriefSettingsStore.cloudApiKey(this),
        )
        val generationFailed = debugScenario == BriefDebugScenario.REAL && !result.snapshot.hasGeneratedNarrative
        providerSetupRequired = required || generationFailed
        if (!providerSetupRequired) return false
        val remembered = BriefStore.presentation(this, username, result.snapshot)
        if (remembered != null && visiblePresentation == null) showRememberedPresentation(remembered)
        listOf(R.id.brief_provider_required_title, R.id.brief_refresh_error_title).forEach { id ->
            findViewById<TextView>(id).setText(if (required) R.string.brief_provider_required_title else R.string.brief_generation_failed_title)
        }
        listOf(R.id.brief_provider_required_body, R.id.brief_refresh_error_body).forEach { id ->
            findViewById<TextView>(id).setText(if (required) R.string.brief_provider_required_body else R.string.brief_generation_failed_body)
        }
        listOf(R.id.brief_provider_required_action, R.id.brief_refresh_error_action).forEach { id ->
            findViewById<TextView>(id).apply {
                setText(if (required) R.string.brief_provider_required_action else R.string.brief_generation_retry)
                setOnClickListener {
                    if (required) showRequiredApiKeyDialog() else {
                        providerSetupRequired = false
                        loadBrief(forceEngine = true, forceAi = true, showSpinner = true)
                    }
                }
            }
        }

        setLoading(false)
        if (required && intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false) && !apiKeyPromptShown) {
            apiKeyPromptShown = true
            showRequiredApiKeyDialog()
        }
        return true
    }

    private fun showRequiredApiKeyDialog() {
        if (apiKeyDialog?.isShowing == true) return
        apiKeyDialog = BriefApiKeyDialog.show(this, required = true) {
            BriefSettingsStore.setProvider(this, BriefProviderMode.AUTO)
            BriefStore.resetAi(this, username)
            providerSetupRequired = false
            loadBrief(
                forceEngine = true,
                forceAi = true,
                showSpinner = true,
                animateOnComplete = true,
            )
        }.also { dialog ->
            dialog.setOnDismissListener { apiKeyDialog = null }
        }
    }

    private fun followerChartCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.brief_card_background)
        setPadding(0, dp(16), 0, 0)

        val stats = TwidgetStore.currentStats(this@TwidgetBriefActivity, username)
        addView(LinearLayout(this@TwidgetBriefActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), 0, dp(20), 0)
            addView(ImageView(this@TwidgetBriefActivity).apply {
                setImageDrawable(AppCompatResources.getDrawable(this@TwidgetBriefActivity, OneUiIconR.drawable.ic_oui_community))
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(primaryText(format(stats.followersCount), 22f, true).apply {
                setPadding(dp(20), 0, 0, 0)
            })
            val delta = TwidgetStore.followersDelta(this@TwidgetBriefActivity, username)
            if (delta != 0L) addView(primaryText(TwidgetStore.signedNumber(delta), 18f).apply {
                setPadding(dp(6), 0, 0, 0)
                setTextColor(getColor(if (delta < 0) R.color.metric_red else R.color.metric_green))
            })
        })
        val full = TwidgetStore.fullHistory(this@TwidgetBriefActivity, username).filter { it.followersKnown }
        val visible = TwidgetStore.chartHistory(this@TwidgetBriefActivity, username, HistoryRange.WEEK)
            .filter { it.followersKnown }
        addView(MetricChartView(this@TwidgetBriefActivity).apply {
            setData(visible, { it.followers })
            setAverageSeries(AccountAverageSeries.values(full, visible, { it.followers }))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(194)))
    }

    private fun postCard(post: PostSummary, sourceAttribution: String = ""): View {
        val eligible = BriefSettingsStore.contentEnabled(this, BriefContentCategory.TWEET_REVIEWS) &&
            BriefPostCoach.reviewablePosts(AnalyticsClient.cached(this, username), username, System.currentTimeMillis())
                .any { it.url == post.url }
        val actions = if (eligible) listOf(BriefReviewAction(R.string.review_view) { openReview(post) }) else emptyList()
        return BriefReviewTweetCard.create(this, username, post = post, compact = true, actions = actions,
            sourceAttribution = sourceAttribution, stackCompactActions = true)
    }

    private fun openReview(post: PostSummary) = startRightSidePopOverActivity(
        BriefTweetReviewActivity.intent(this, BriefReviewTarget(username, postUrl = post.url)))

    private fun addMetric(row: LinearLayout, iconRes: Int, value: Long, label: String) {
        row.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            contentDescription = "$label: ${TwidgetStore.compactNumber(value)}"
            addView(ImageView(context).apply {
                setImageDrawable(AppCompatResources.getDrawable(context, iconRes))
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(primaryText(TwidgetStore.compactNumber(value), 14f).apply {
                setPadding(dp(4), 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun topFollowersCard(card: BriefCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.brief_card_background)
        isClickable = true
        isFocusable = true
        applyBriefCardRipple()
        setOnClickListener {
            startRightSidePopOverActivity(
                Intent(this@TwidgetBriefActivity, TopFollowersBrowseActivity::class.java)
                    .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, username),
            )
        }
        setPadding(0, dp(12), 0, dp(6))
        val followers = TopFollowersStore.read(this@TwidgetBriefActivity, username).top.take(3)
        if (followers.isEmpty()) {
            addView(supportingText(getString(R.string.brief_top_followers_empty), 14f).apply {
                setPadding(dp(20), dp(12), dp(20), dp(18))
            })
            return@apply
        }
        followers.forEachIndexed { index, follower ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(10), dp(20), dp(10))
                minimumHeight = dp(68)
                addView(TextView(context).apply {
                    text = "${index + 1}"
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextColor(getColor(R.color.oneui_text_primary))
                    textSize = 30f
                    typeface = Typeface.create("sec", Typeface.NORMAL)
                }, LinearLayout.LayoutParams(dp(24), dp(40)))
                addView(ImageView(context).apply {
                    ProfileImageLoader.loadInto(context, this, follower.avatarUrl)
                }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, 0, 0)
                    addView(primaryText(follower.name.ifBlank { "@${follower.username}" }, 20f, true).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(supportingText("@${follower.username}", 14f))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply {
                    setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_community))
                    imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
                }, LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(supportingText(TwidgetStore.compactNumber(follower.followers), 14f).apply {
                    setPadding(dp(4), 0, 0, 0)
                })
            })
            if (index < followers.lastIndex) addView(View(this@TwidgetBriefActivity).apply {
                setBackgroundColor(getColor(R.color.brief_divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            })
        }
    }

    private fun render(
        snapshot: BriefSnapshot,
        analytics: PostAnalytics? = AnalyticsClient.cached(this, username),
    ) {
        renderedSnapshot = snapshot
        visiblePresentation = BriefStore.presentation(this, username, snapshot)
        val summary = BriefEditorialSummary.from(snapshot, BriefStrings.from(this))
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
            .showStaticText(getString(R.string.brief_summary_title_week))
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body).showStaticText(summary.body)
        val columns = configureResponsiveLayout()
        val container = findViewById<LinearLayout>(R.id.brief_cards)
        container.removeAllViews()
        // Keep every review preview independent so masonry can balance unequal tweet heights.
        val reviews = if (debugScenario == BriefDebugScenario.REAL && snapshot.hasGeneratedNarrative) {
            BriefPostCoachSection.create(this, username, analytics,
                excludedPostUrls = snapshot.cards.mapNotNull { resolveBriefPost(it, analytics)?.url }.toSet(),
                editDraft = ::openDraft)
        } else null
        refreshReviewPreviews = reviews?.refreshDescriptions
        val sections = buildList {
            reviews?.let { items ->
                items.previews.forEach { add(it to 20) }
                add(items.libraryCard to 20)
            }
            if (snapshot.upcomingTweets.isNotEmpty()) {
                add(upcomingSection(snapshot.upcomingTweets) to 24)
            }
            snapshot.cards.forEach { card -> add(cardSection(card, analytics) to 20) }
        }.mapIndexed { index, (section, topMargin) ->
            section to if (index == 0) 32 else topMargin
        }
        entranceSections = sections.map { it.first }
        if (columns == 1) {
            container.orientation = LinearLayout.VERTICAL
            sections.forEach { (section, topMargin) ->
                container.addView(section, matchWrap(top = topMargin))
            }
        } else {
            container.orientation = LinearLayout.HORIZONTAL
            val masonryColumns = List(columns) { columnIndex ->
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    container.addView(
                        this,
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (columnIndex == 0) rightMargin = dp(10) else leftMargin = dp(10)
                        },
                    )
                }
            }
            val columnHeights = IntArray(columns)
            val content = findViewById<LinearLayout>(R.id.brief_content)
            val configuredWidth = content.layoutParams.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels
            val availableWidth = configuredWidth - content.paddingLeft - content.paddingRight
            val columnWidth = (
                availableWidth - dp(MASONRY_COLUMN_GAP_DP) * (columns - 1)
                ) / columns
            sections.forEach { (section, topMargin) ->
                section.measure(
                    View.MeasureSpec.makeMeasureSpec(columnWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val targetColumn = BriefLayoutPolicy.shortestColumn(columnHeights)
                masonryColumns[targetColumn].addView(section, matchWrap(top = topMargin))
                columnHeights[targetColumn] += section.measuredHeight + dp(topMargin)
            }
        }
    }

    // Scheduled copy is rendered locally and never enters an AI prompt.
    private fun upcomingSection(tweets: List<BriefUpcomingTweet>): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionLabel(getString(R.string.brief_upcoming_scheduled_tweets)))
        tweets.forEachIndexed { index, tweet ->
            addView(scheduledPostCard(tweet), matchWrap(top = if (index == 0) 10 else 20))
        }
    }

    private fun scheduledPostCard(tweet: BriefUpcomingTweet): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.brief_card_background)
        applyBriefCardRipple()
        setOnClickListener { openSchedule(tweet) }

        val stats = TwidgetStore.currentStats(this@TwidgetBriefActivity, username)
        val scheduledPost = ScheduleStore(this@TwidgetBriefActivity).get(tweet.id)
        val firstPost = scheduledPost?.thread?.firstOrNull()

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(context).apply {
                ProfileImageLoader.loadInto(context, this, stats.profileImage)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@TwidgetBriefActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(primaryText(stats.fullName.ifBlank { "@$username" }, 14f, true))
                addView(supportingText("@$username · ${scheduleDate(tweet.scheduledAt)}", 12f))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        addView(primaryText(firstPost?.text.orEmpty().ifBlank {
            tweet.preview.ifBlank { getString(R.string.brief_scheduled_tweet_placeholder) }
        }, 14f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrap(top = 10))

        firstPost?.media?.firstOrNull()?.let { media ->
            addView(com.tjg.twidget.ui.MediaAspectImageView(context).apply {
                contentDescription = getString(R.string.post_media)
                scaleType = ImageView.ScaleType.FIT_CENTER
                when (media) {
                    is LocalUriMedia -> {
                        background = AppCompatResources.getDrawable(context, R.drawable.schedule_media_preview_bg)
                        outlineProvider = ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                        runCatching { setImageURI(Uri.parse(media.uri)) }
                    }
                    is PublicUrlMedia -> ProfileImageLoader.loadMediaInto(
                        context,
                        this,
                        media.displayUrl,
                        dp(14),
                    )
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) })
        }

        val details = buildList {
            if (tweet.threadCount > 1) {
                add(resources.getQuantityString(R.plurals.brief_draft_thread_count, tweet.threadCount, tweet.threadCount))
            }
            if (tweet.mediaCount > 0) add(getString(R.string.brief_scheduled_media_count, tweet.mediaCount))
            add(
                getString(
                    if (tweet.provider == ScheduleProvider.BUFFER) R.string.schedule_provider_buffer else R.string.schedule_provider_local,
                ),
            )
        }.joinToString(" · ")
        addView(supportingText(details, 12f), matchWrap(top = 10))
    }

    private fun draftPostCard(post: ScheduledPost): View {
        val actions = buildList {
            add(BriefReviewAction(R.string.coach_edit_draft, false) { openDraft(post) })
            if (BriefSettingsStore.contentEnabled(this@TwidgetBriefActivity, BriefContentCategory.TWEET_REVIEWS)) {
                add(BriefReviewAction(R.string.review_view) {
                    startRightSidePopOverActivity(BriefTweetReviewActivity.intent(this@TwidgetBriefActivity,
                        BriefReviewTarget(username, draftId = post.id)))
                })
            }
        }
        return BriefReviewTweetCard.create(this, username, draft = post, compact = true, actions = actions,
            stackCompactActions = true)
    }

    private fun scheduleDate(timestamp: Long): String = if (timestamp > 0L) {
        AppLocales.formatDate(timestamp, "EMMMdjm")
    } else {
        getString(R.string.time_unavailable)
    }

    private fun openSchedule(tweet: BriefUpcomingTweet) {
        reloadOnResume = true
        startRightSidePopOverActivity(
            Intent(this, ScheduleComposeActivity::class.java)
                .putExtra(ScheduleComposeActivity.EXTRA_USERNAME, username)
                .putExtra(ScheduleComposeActivity.EXTRA_SCHEDULE_ID, tweet.id),
        )
    }

    private fun openDraft(post: ScheduledPost) {
        reloadOnResume = true
        startRightSidePopOverActivity(
            Intent(this, ScheduleComposeActivity::class.java)
                .putExtra(ScheduleComposeActivity.EXTRA_USERNAME, username)
                .putExtra(ScheduleComposeActivity.EXTRA_SCHEDULE_ID, post.id),
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderedSnapshot?.let(::render) ?: configureResponsiveLayout()
    }

    override fun allowsPopOverPresentation(): Boolean = false

    private fun configureResponsiveLayout(): Int {
        val columns = BriefLayoutPolicy.columnCount(resources.configuration.screenWidthDp)
        findViewById<LinearLayout>(R.id.brief_content).layoutParams =
            (findViewById<LinearLayout>(R.id.brief_content).layoutParams as FrameLayout.LayoutParams).apply {
                width = if (columns == 1) {
                    FrameLayout.LayoutParams.MATCH_PARENT
                } else {
                    minOf(
                        resources.displayMetrics.widthPixels - dp(20),
                        dp(BriefLayoutPolicy.MAX_CONTENT_WIDTH_DP),
                    )
                }
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        findViewById<LinearLayout>(R.id.brief_footer).layoutParams =
            (findViewById<LinearLayout>(R.id.brief_footer).layoutParams as LinearLayout.LayoutParams).apply {
                width = if (columns == 1) {
                    LinearLayout.LayoutParams.MATCH_PARENT
                } else {
                    dp(488)
                }
                gravity = Gravity.CENTER_HORIZONTAL
            }
        return columns
    }

    private fun cardSection(
        card: BriefCard,
        analytics: PostAnalytics?,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val followThroughPost = if (card.type == BriefCardType.POST_FOLLOW_THROUGH) {
            analytics?.recentPosts?.firstOrNull { it.url == card.actionData }
                ?: analytics?.best?.takeIf { debugScenario == BriefDebugScenario.POST_FOLLOW_THROUGH }
        } else {
            null
        }
        val guideDraft = if (
            card.type == BriefCardType.SCHEDULE_GUIDE && card.id.startsWith("schedule-drafts-")
        ) {
            readyDraftForCard(card)
        } else {
            null
        }
        val selectedPost = resolveBriefPost(card, analytics)
        addView(sectionLabel(card.body.ifBlank { card.title }).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        val content = when (card.type) {
            BriefCardType.MILESTONE -> milestoneCard(card)
            BriefCardType.STREAK -> StreakCardFactory.createBrief(
                this@TwidgetBriefActivity,
                DailyStreakStore.snapshot(this@TwidgetBriefActivity, username),
            )
            BriefCardType.FOLLOWER_CONTEXT, BriefCardType.POST_CONTEXT -> contextStatCard(card)
            BriefCardType.GROWTH, BriefCardType.SLOWDOWN -> followerChartCard()
            BriefCardType.POST -> selectedPost?.let { postCard(it) }
                ?: genericCard(card)
            BriefCardType.WORST_POST -> selectedPost?.let { postCard(it) }
                ?: genericCard(card)
            BriefCardType.POST_FOLLOW_THROUGH -> followThroughPost
                ?.let { postCard(it, card.sourceAttribution) }
                ?: genericCard(card)
            BriefCardType.SCHEDULE_GUIDE -> guideDraft?.let(::draftPostCard) ?: compactGuideAction(card)
            BriefCardType.POSTING_GUIDE -> compactGuideAction(card)
            BriefCardType.TOP_FOLLOWER -> topFollowersCard(card)
            BriefCardType.INACTIVITY -> genericCard(card, warm = true)
            else -> genericCard(card)
        }
        addView(content, matchWrap(top = 10))
    }

    private fun readyDraftForCard(card: BriefCard): ScheduledPost? {
        val drafts = ScheduleStore(this).listForAccount(username).filter { post ->
            post.status == ScheduleStatus.DRAFT &&
                post.thread.any { it.text.isNotBlank() || it.media.isNotEmpty() }
        }
        return drafts.firstOrNull { it.id == card.actionData } ?: drafts.firstOrNull()
    }

    private fun contextStatCard(card: BriefCard): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(22))
        background = radialCard(
            if (isNight()) Color.rgb(25, 61, 77) else Color.rgb(211, 235, 245),
            getColor(R.color.oneui_card_bg),
        )
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(primaryText(card.title, 16f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                setImageResource(if (card.type == BriefCardType.FOLLOWER_CONTEXT) OneUiIconR.drawable.ic_oui_community else OneUiIconR.drawable.ic_oui_equalizer_2)
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_secondary))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        })
        card.metrics.firstOrNull()?.let { metric ->
            addView(statValue(metric.value, 54f), matchWrap(top = 12))
            addView(supportingText(metric.label, 13f), matchWrap(top = 2))
        }
        if (card.metrics.size > 1) {
            addView(View(context).apply { setBackgroundColor(getColor(R.color.brief_divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(20); bottomMargin = dp(16) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                card.metrics.drop(1).forEachIndexed { index, metric ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(statValue(metric.value, 28f), matchWrap())
                        addView(supportingText(metric.label, 12f), matchWrap(top = 4))
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = dp(20)
                    })
                }
            }, matchWrap())
        }
    }

    private fun statValue(value: String, size: Float) = CardValueTextView(this).apply {
        text = value
        textSize = size
        configure(size, 124)
        setTextColor(getColor(R.color.oneui_text_primary))
    }

    private fun genericCard(card: BriefCard, warm: Boolean = false): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(19), dp(16), dp(19), dp(16))
        background = if (warm) radialCard(
            Color.rgb(255, 189, 157),
            getColor(R.color.oneui_card_bg),
        ) else AppCompatResources.getDrawable(context, R.drawable.brief_card_background)
        addView(primaryText(card.title, 18f, bold = true))
        actionLabel(card)?.let { label ->
            addView(primaryText("$label  ›", 13f, bold = true).apply {
                setTextColor(getColor(R.color.oneui_accent))
            }, matchWrap(top = 12))
        }
        if (card.action != BriefCardAction.NONE) {
            isClickable = true
            isFocusable = true
            applyBriefCardRipple()
            setOnClickListener { performCardAction(card) }
        }
    }

    private fun compactGuideAction(card: BriefCard): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL

        val openPrimary = View.OnClickListener { performCardAction(card) }
        val openSchedule = View.OnClickListener { openScheduledTweets() }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundResource(R.drawable.brief_guide_action_surface)
            clipToOutline = true
            applyBriefCardRipple()
            isClickable = card.action != BriefCardAction.NONE
            isFocusable = isClickable
            if (isClickable) setOnClickListener(openPrimary)
            addView(primaryText(actionLabel(card) ?: card.title, 18f, bold = true))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(10)
        })

        addView(ImageView(context).apply {
            setImageResource(OneUiIconR.drawable.ic_oui_open)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.brief_guide_action_button)
            clipToOutline = true
            applyBriefCardRipple()
            contentDescription = getString(R.string.brief_open_scheduled_tweets)
            isClickable = true
            isFocusable = true
            setOnClickListener(openSchedule)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
    }

    private fun actionLabel(card: BriefCard): String? = when (card.action) {
        BriefCardAction.NONE -> null
        BriefCardAction.OPEN_SCHEDULER -> getString(R.string.brief_action_review_schedule)
        BriefCardAction.COMPOSE_TWEET -> getString(R.string.brief_action_draft_tweet)
        BriefCardAction.OPEN_POST -> getString(R.string.brief_action_open_tweet)
    }

    private fun performCardAction(card: BriefCard) {
        when (card.action) {
            BriefCardAction.NONE -> Unit
            BriefCardAction.OPEN_SCHEDULER -> openScheduledTweets()
            BriefCardAction.COMPOSE_TWEET -> {
                reloadOnResume = true
                startRightSidePopOverActivity(
                    Intent(this, ScheduleComposeActivity::class.java)
                        .putExtra(ScheduleComposeActivity.EXTRA_USERNAME, username),
                )
            }
            BriefCardAction.OPEN_POST -> card.actionData
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
    }

    private fun openScheduledTweets() {
        reloadOnResume = true
        startRightSidePopOverActivity(
            Intent(this, ScheduleActivity::class.java)
                .putExtra(ScheduleActivity.EXTRA_USERNAME, username),
        )
    }

    private fun View.applyBriefCardRipple() {
        foreground = AppCompatResources.getDrawable(context, R.drawable.brief_card_ripple)
    }

    private fun milestoneCard(card: BriefCard): View {
        val root = LayoutInflater.from(this).inflate(R.layout.milestone_card, null, false)
        val isSetup = card.actionData == BRIEF_MILESTONE_SETUP_ACTION
        val settings = if (isSetup) null else milestoneSettings(card)
        val metric = settings?.let {
            MilestoneMetricResolver.resolve(
                context = this,
                account = username,
                metric = it.metric,
                stats = TwidgetStore.currentStats(this, username),
                history = TwidgetStore.fullHistory(this, username),
                analytics = AnalyticsClient.cached(this, username),
                imported = ImportedAnalyticsStore.all(this, username),
            )
        }
        val progress = settings?.let { MilestonePolicy.progress(metric?.value, it.target) } ?: 0
        val state = metric?.let { MilestonePolicy.performanceState(it.history) }
            ?: MilestonePerformanceState.NEUTRAL
        val accent = when (state) {
            MilestonePerformanceState.ACCELERATING -> Color.rgb(15, 207, 110)
            MilestonePerformanceState.DECELERATING -> Color.rgb(255, 103, 31)
            MilestonePerformanceState.NEUTRAL -> Color.rgb(24, 129, 255)
        }
        val glow = when (state) {
            MilestonePerformanceState.ACCELERATING -> Color.rgb(173, 255, 213)
            MilestonePerformanceState.DECELERATING -> Color.rgb(255, 196, 168)
            MilestonePerformanceState.NEUTRAL -> Color.rgb(141, 204, 255)
        }
        root.background = MilestoneCardBackgroundDrawable(
            glowColor = if (isNight()) darken(glow) else glow,
            surfaceColor = getColor(R.color.oneui_card_bg),
            radiusPx = dp(28).toFloat(),
        )
        root.findViewById<MilestoneArcView>(R.id.milestone_arc).apply {
            this.progress = progress
            progressColor = accent
        }
        BriefGoalCardCopy.bind(this, root, username, settings, metric)
        val openEditor = View.OnClickListener {
            if (isSetup) {
                reloadOnResume = true
                startRightSidePopOverActivity(MilestoneGoalActivity.intent(this, username))
            } else {
                MilestoneGoalDialog.show(this, username, settings!!.metric) {
                    loadBrief(forceEngine = true)
                }
            }
        }
        root.setOnClickListener(openEditor)
        root.findViewById<ImageButton>(R.id.milestone_edit).apply {
            visibility = if (isSetup) View.GONE else View.VISIBLE
            setOnClickListener(openEditor)
        }
        return root
    }

    private fun milestoneSettings(card: BriefCard) =
        MilestoneGoalStore.readAll(this, username)
            .firstOrNull { it.metric.storageId == card.actionData }
            ?: MilestoneGoalStore.read(this, username)

    private fun radialCard(start: Int, end: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(320).toFloat()
        setGradientCenter(0f, .5f)
        cornerRadius = dp(28).toFloat()
        colors = intArrayOf(start, end)
    }

    private fun isNight(): Boolean = resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun darken(color: Int): Int = Color.rgb(
        (Color.red(color) * .3f).toInt(),
        (Color.green(color) * .3f).toInt(),
        (Color.blue(color) * .3f).toInt(),
    )

    private fun showProviderInfo() {
        val providerNote = renderedSnapshot?.providerMessage?.ifBlank { getString(R.string.brief_provider_note_template) }
            ?: visiblePresentation?.let {
                getString(R.string.brief_saved_summary_info,
                    AppLocales.formatDate(it.generatedAt, "MMMdjm"))
            } ?: return
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.brief_ai_disclaimer)
            .setMessage(
                getString(
                    R.string.brief_ai_disclaimer_body,
                    providerNote,
                ),
            )
            .setNegativeButton(android.R.string.cancel, null)
        if (localStatus == BriefLocalStatus.DOWNLOADABLE) {
            builder.setPositiveButton(R.string.brief_prepare_local) { _, _ -> downloadLocalModel() }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun downloadLocalModel() {
        lifecycleScope.launch {
            setLoading(true)
            var entrancePrepared = false
            try {
                localStatus = BriefLocalStatus.DOWNLOADING
                val downloaded = BriefAiCoordinator.downloadLocalModel(this@TwidgetBriefActivity) { }
                if (!downloaded) {
                    localStatus = BriefLocalStatus.DOWNLOADABLE
                    return@launch
                }
                val source = BriefStore.read(this@TwidgetBriefActivity, username) ?: return@launch
                val result = BriefAiCoordinator.enrich(this@TwidgetBriefActivity, source, force = true)
                localStatus = result.localStatus
                render(result.snapshot)
                prepareBriefEntrance()
                entrancePrepared = true
            } finally {
                setLoading(false)
                if (entrancePrepared) startPreparedBriefEntrance()
            }
        }
    }

    private fun prepareBriefEntrance() {
        val title = findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
        val body = findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body)
        title.prepareNowBriefTitleMotion()
        body.prepareNowBriefMotion()
        entranceSections.forEach { prepareEntranceView(it, CARD_LIFT_DP) }
        prepareEntranceView(findViewById(R.id.brief_footer), COPY_LIFT_DP)
    }

    private fun prepareOnboardingBackgroundTransition() {
        val root = findViewById<View>(R.id.brief_root)
        val startTop = getColor(R.color.brief_onboarding_top)
        val startBottom = getColor(R.color.brief_onboarding_bottom)
        val endTop = getColor(R.color.brief_screen_top)
        val endBottom = getColor(R.color.brief_screen_bottom)
        val background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(startTop, startBottom),
        )
        root.background = background
        root.postOnAnimation {
            if (isFinishing || isDestroyed) return@postOnAnimation
            val evaluator = ArgbEvaluator()
            backgroundAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = BACKGROUND_TRANSITION_DURATION_MS
                interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
                addUpdateListener { animator ->
                    val fraction = animator.animatedFraction
                    background.colors = intArrayOf(
                        evaluator.evaluate(fraction, startTop, endTop) as Int,
                        evaluator.evaluate(fraction, startBottom, endBottom) as Int,
                    )
                }
                start()
            }
        }
    }

    private fun startPreparedBriefEntrance() {
        findViewById<View>(R.id.brief_scroll).post {
            if (isFinishing || isDestroyed) return@post
            findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
                .startNowBriefTitleMotion(TITLE_DELAY_MS)
            findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body)
                .startNowBriefMotion(COPY_DELAY_MS) {
                    entranceSections.forEach { section ->
                        startNowBriefCardEntrance(section, 0L)
                    }
                }
            startEntranceView(
                findViewById(R.id.brief_footer),
                FOOTER_DELAY_MS,
                COPY_DURATION_MS,
                PathInterpolator(0.2f, 0f, 0f, 1f),
            )
        }
    }

    private fun prepareEntranceView(view: View, liftDp: Int) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = dp(liftDp).toFloat()
    }

    private fun startEntranceView(
        view: View,
        delay: Long,
        duration: Long,
        interpolator: PathInterpolator,
    ) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
    }

    private fun startNowBriefCardEntrance(view: View, delay: Long) {
        val alpha = ObjectAnimator.ofFloat(view, View.ALPHA, 1f).apply {
            duration = NOW_BRIEF_CARD_ALPHA_DURATION_MS
        }
        val translation = ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f).apply {
            duration = NOW_BRIEF_CARD_TRANSLATE_DURATION_MS
            interpolator = PathInterpolator(0.22f, 0.25f, 0f, 1f)
        }
        AnimatorSet().apply {
            startDelay = delay
            playTogether(alpha, translation)
            start()
        }
    }

    private fun sectionLabel(value: String) = primaryText(value, 14f).apply {
        setPadding(dp(13), 0, dp(13), 0)
    }

    private fun primaryText(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        includeFontPadding = false
        setTextColor(getColor(R.color.oneui_text_primary))
        textSize = size
        typeface = Typeface.create("sec", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun supportingText(value: String, size: Float) = TextView(this).apply {
        text = value
        includeFontPadding = false
        setTextColor(getColor(R.color.brief_text_supporting))
        textSize = size
        typeface = Typeface.create("sec", Typeface.NORMAL)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun postDate(post: PostSummary): String = if (post.timestamp > 0L) {
        AppLocales.formatDate(post.timestamp, "MMMdjm")
    } else post.createdAt

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun format(value: Long): String = NumberFormat.getIntegerInstance(com.tjg.twidget.core.AppLocales.applicationLocale()).format(value)

    companion object {
        private const val MASONRY_COLUMN_GAP_DP = 20
        private const val COPY_LIFT_DP = 12
        private const val CARD_LIFT_DP = 15
        private const val TITLE_DELAY_MS = 0L
        private const val COPY_DELAY_MS = 220L
        private const val FOOTER_DELAY_MS = 420L
        private const val COPY_DURATION_MS = 400L
        private const val NOW_BRIEF_CARD_ALPHA_DURATION_MS = 950L
        private const val NOW_BRIEF_CARD_TRANSLATE_DURATION_MS = 500L
        private const val BACKGROUND_TRANSITION_DURATION_MS = 560L
        const val EXTRA_USERNAME = "username"
        private const val EXTRA_DEBUG_SCENARIO = "brief_debug_scenario"
        private const val EXTRA_FORCE_REFRESH = "brief_force_refresh"
        private const val EXTRA_WAIT_FOR_LAUNCH_GENERATION = "brief_wait_for_launch_generation"
        private const val EXTRA_FROM_ONBOARDING = "brief_from_onboarding"
        private const val EXTRA_API_KEY_PROMPT_SHOWN = "brief_api_key_prompt_shown"

        fun intent(context: Context, username: String): Intent =
            if (BriefSettingsStore.onboardingComplete(context)) {
                contentIntent(context, username)
            } else {
                BriefOnboardingActivity.intent(context, username)
            }

        fun contentIntent(
            context: Context,
            username: String,
            waitForLaunchGeneration: Boolean = false,
            fromOnboarding: Boolean = false,
            apiKeyPromptAlreadyShown: Boolean = false,
        ) = Intent(context, TwidgetBriefActivity::class.java)
            .putExtra(EXTRA_USERNAME, username)
            .putExtra(EXTRA_WAIT_FOR_LAUNCH_GENERATION, waitForLaunchGeneration)
            .putExtra(EXTRA_FROM_ONBOARDING, fromOnboarding)
            .putExtra(EXTRA_API_KEY_PROMPT_SHOWN, apiKeyPromptAlreadyShown)

        fun debugIntent(context: Context, username: String, scenario: BriefDebugScenario) =
            contentIntent(context, username).putExtra(EXTRA_DEBUG_SCENARIO, scenario.storageId)

        fun refreshIntent(context: Context, username: String) =
            contentIntent(context, username).putExtra(EXTRA_FORCE_REFRESH, true)
    }
}
