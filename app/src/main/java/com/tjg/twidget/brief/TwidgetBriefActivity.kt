package com.tjg.twidget.brief

import android.view.ViewGroup
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
import com.tjg.twidget.settings.BriefSettingsActivity
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.CardValueTextView
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.VerifiedBadge
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
    private val archiveUpdateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!intent?.getStringExtra("username").equals(username, true) || isFinishing || isDestroyed ||
                debugScenario != BriefDebugScenario.REAL || renderedSnapshot == null) return
            val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.brief_scroll)
            val y = scroll.scrollY
            render(BriefEngine.rebuild(this@TwidgetBriefActivity, username))
            scroll.post { scroll.scrollTo(0, y) }
        }
    }

    override fun onStart() {
        super.onStart()
        androidx.core.content.ContextCompat.registerReceiver(this, archiveUpdateReceiver,
            android.content.IntentFilter(com.tjg.twidget.analytics.HistoricalAnalyticsWorker.ACTION_UPDATED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(archiveUpdateReceiver) }
        super.onStop()
    }
    private var visiblePresentation: BriefPresentation? = null
    private var refreshReviewPreviews: (() -> Unit)? = null
    private val rankedTweetDescriptions = mutableListOf<Triple<PostSummary, TextView, String>>()
    private fun refreshTweetRankDescriptions() {
        val bindings = rankedTweetDescriptions.toList()
        lifecycleScope.launch {
            val labels = withContext(Dispatchers.IO) {
                bindings.map { (post, _, base) ->
                    val saved = BriefPublishedReviewStore.read(this@TwidgetBriefActivity, BriefReviewTarget(username, postUrl = post.url))
                    val evidence = saved?.let { BriefReviewSubjects.withRanking(this@TwidgetBriefActivity, it.subject).input.reviewEvidence }
                        ?: AnalyticsClient.cached(this@TwidgetBriefActivity, username)?.let { BriefReviewEvidence.create(post, it, emptyList()) }
                    evidence?.viewsRank?.let { "$base " + getString(R.string.review_preview_rank, it, evidence.viewsRankTotal) } ?: base
                }
            }
            bindings.zip(labels).forEach { (binding, label) -> binding.second.text = label }
        }
    }
    private var localStatus = BriefLocalStatus.UNAVAILABLE
    private var debugScenario: BriefDebugScenario = BriefDebugScenario.REAL
    private var reloadOnResume = false
    private var checkContentOnResume = false
    private var entranceSections: List<View> = emptyList()
    private val cardEntranceAnimations = mutableListOf<AnimatorSet>()
    private var entranceInProgress = false
    private var entranceStarted = false
    private var entranceGeneration = 0
    private var pendingEntranceRender: Pair<BriefSnapshot, PostAnalytics?>? = null
    private var loadingStatusIndex = 0
    private var loadingStatusRunnable: Runnable? = null
    private val loadingStatusAppearInterpolator = PathInterpolator(0.22f, 0f, 0.18f, 1f)
    private val loadingStatusDisappearInterpolator = PathInterpolator(0.42f, 0f, 1f, 1f)
    private var providerSetupRequired = false
    private var apiKeyPromptShown = false
    private var apiKeyDialog: AlertDialog? = null

    private val providerSignIn = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && BriefSettingsStore.cloudApiKey(this).isNotBlank()) providerConfigured()
    }

    private fun providerConfigured() {
        BriefSettingsStore.setProvider(this, BriefProviderMode.AUTO)
        BriefStore.resetAi(this, username)
        providerSetupRequired = false
        loadBrief(forceEngine = true, forceAi = true, showSpinner = true, animateOnComplete = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_twidget_brief)
        findViewById<View>(R.id.brief_root).addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) view.post { reflowForViewport() }
        }
        backgroundUnlockProgress = savedInstanceState?.getFloat("brief_background_unlock")
            ?: if (intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)) 0f else .45f
        backgroundUnlockStarted = savedInstanceState?.getBoolean("brief_background_unlock_started") ?: false
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
        if (debugScenario == BriefDebugScenario.REAL) BriefSettingsStore.setEnabled(this, true)

        bindChrome()
        applyContextBackground()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BriefPublishedReviewStore.updates.collect { refreshReviewPreviews?.invoke() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var hour = java.time.ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0)
                while (true) {
                    kotlinx.coroutines.delay(60_000)
                    val next = java.time.ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0)
                    if (next != hour) {
                        hour = next
                        applyContextBackground()
                        renderedSnapshot?.let { render(it) }
                    }
                }
            }
        }
        showLaunchShell()
        afterFirstFrame {
            if (savedInstanceState == null) {
                com.tjg.twidget.ui.TwidgetHaptics.quickRise(findViewById(R.id.brief_root))
            }
            loadInitialBrief()
        }
    }

    private fun loadInitialBrief() {
        if (debugScenario == BriefDebugScenario.COMPONENTS) {
            render(BriefSnapshot(username, System.currentTimeMillis(), 0, 0, 0, 130, 20, 6, 0, 0,
                emptyList(), topFollowerRanks = emptyMap()))
            setLoading(false)
            return
        }
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
                ((initial.first.hasGeneratedNarrative || canUpdateIncrementally(initial.first)) &&
                BriefRunCache.providerMatches(BriefSettingsStore.provider(this@TwidgetBriefActivity), initial.first.providerUsed) ||
                    BriefGenerationCadence.isCovered(this@TwidgetBriefActivity, initial.first)))
                initial.first to initial.third else null
            if (cached != null) {
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
                        force = false,
                    )
                val result = runCatching { generation.await() }.getOrElse {
                    val source = withContext(Dispatchers.IO) {
                        BriefEngine.rebuild(this@TwidgetBriefActivity, username, force = true)
                    }
                    BriefAiResult(source, BriefLocalStatus.UNAVAILABLE)
                }
                localStatus = result.localStatus
                if (canUpdateIncrementally(result.snapshot) && result.snapshot != renderedSnapshot) render(result.snapshot)
                if (showProviderSetupIfRequired(result, allowCovered = true)) return@launch
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
        applyContextBackground()
        startBackgroundMotion()
        if (backgroundUnlockStarted || !intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)) startBackgroundUnlock()
        if (renderedTimingHour != java.time.ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0)) {
            renderedSnapshot?.let { render(it) }
        }
        syncChatGptLoginCards()
        if (reloadOnResume) {
            reloadOnResume = false
            val contentChanged = debugScenario == BriefDebugScenario.REAL &&
                BriefSettingsStore.contentRegenerationPending(this)
            loadBrief(
                forceEngine = true,
                forceAi = false,
                showSpinner = contentChanged,
                animateOnComplete = true,
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
                renderedSnapshot?.let {
                    render(it)
                    prepareBriefEntrance()
                    startPreparedBriefEntrance()
                }
            }
            return
        }
        // Returning from a child page/popover keeps the current presentation.
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
                val ready = !forceAi && (BriefGenerationCadence.isCovered(this@TwidgetBriefActivity, source) ||
                    source.hasGeneratedNarrative && BriefAiCachePolicy.isFresh(source) &&
                    BriefRunCache.providerMatches(BriefSettingsStore.provider(this@TwidgetBriefActivity), source.providerUsed))
                val incremental = !forceAi && debugScenario == BriefDebugScenario.REAL && canUpdateIncrementally(source)
                if (incremental) {
                    // A cached opening has already prepared its reveal. Rebinding
                    // identical content clears that motion and replaces its cards.
                    if (source != renderedSnapshot) render(source)
                    setLoading(false)
                } else if (debugScenario == BriefDebugScenario.REAL && !ready) {
                    val remembered = withContext(Dispatchers.IO) { BriefStore.presentation(this@TwidgetBriefActivity, username, source) }
                    if (remembered != null) {
                        if (renderedSnapshot == null) showRememberedPresentation(remembered)
                    } else {
                        visiblePresentation = null
                        renderedSnapshot = null
                    }
                }
                if (!ready && !incremental) setLoading(true)
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
                if (canUpdateIncrementally(result.snapshot) && result.snapshot != renderedSnapshot) render(result.snapshot)
                if (showProviderSetupIfRequired(result, allowCovered = !forceAi)) return@launch
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

    private fun canUpdateIncrementally(source: BriefSnapshot): Boolean =
        source.summaryEvidenceKey.isNotBlank() && source.providerUsed != BriefProviderUsed.TEMPLATE &&
            BriefRunCache.providerMatches(BriefSettingsStore.provider(this), source.providerUsed)

    override fun onPause() {
        backgroundMotion?.cancel()
        backgroundMotion = null
        backgroundUnlockAnimator?.cancel()
        backgroundUnlockAnimator = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("brief_background_unlock", backgroundUnlockProgress)
        outState.putBoolean("brief_background_unlock_started", backgroundUnlockStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        entranceGeneration++
        entranceInProgress = false
        pendingEntranceRender = null
        cardEntranceAnimations.forEach { it.cancel() }
        cardEntranceAnimations.clear()
        apiKeyDialog?.dismiss()
        apiKeyDialog = null
        stopLoadingStatusRotation()
        super.onDestroy()
    }

    private fun showRememberedPresentation(presentation: BriefPresentation) {
        visiblePresentation = presentation
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
            .showStaticText(presentation.title)
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body).showStaticText(presentation.body)
        findViewById<LinearLayout>(R.id.brief_cards).removeAllViews()
        entranceSections = emptyList()
        syncChatGptLoginCards()
    }

    private fun chatGptLoginCard(): View = BriefChatGptLinkCard.create(this) {
        providerSignIn.launch(BriefChatGptActivity.forSetup(this))
    }

    /** Keep login available even before a first Brief exists, and refresh it after account settings return. */
    private fun syncChatGptLoginCards() {
        if (debugScenario != BriefDebugScenario.REAL) return
        val show = BriefChatGptLinkCard.shouldShow(this)
        val cards = findViewById<LinearLayout>(R.id.brief_cards)
        val current = cards.findViewWithTag<View>(BriefChatGptLinkCard.TAG)
        if (!show && current != null) (current.parent as? android.view.ViewGroup)?.removeView(current)
        else if (show && current == null && visiblePresentation != null) {
            if (cards.orientation == LinearLayout.VERTICAL) cards.addView(chatGptLoginCard(), 0, matchWrap(top = 32))
            else renderedSnapshot?.let { render(it) }
        }
        findViewById<LinearLayout>(R.id.brief_provider_login).apply {
            visibility = if (show) View.VISIBLE else View.GONE
            if (show && childCount == 0) addView(chatGptLoginCard())
            if (!show) removeAllViews()
        }
    }

    private fun setLoading(loading: Boolean) {
        syncChatGptLoginCards()
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

    private fun showProviderSetupIfRequired(result: BriefAiResult, allowCovered: Boolean = false): Boolean {
        if (allowCovered && BriefGenerationCadence.isCovered(this, result.snapshot)) {
            providerSetupRequired = false
            return false
        }
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
                        val incremental = renderedSnapshot?.let(::canUpdateIncrementally) == true
                        loadBrief(forceEngine = true, forceAi = !incremental, showSpinner = !incremental)
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
        apiKeyDialog = BriefProviderSetupDialog.show(this,
            onChatGptSignIn = { providerSignIn.launch(BriefChatGptActivity.forSetup(this)) },
            onConfigured = ::providerConfigured,
        ).also { dialog ->
            dialog.setOnDismissListener { apiKeyDialog = null }
        }
    }

    private fun followerChartCard(previewHistory: List<com.tjg.twidget.data.HistorySample>? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.brief_card_background)
        com.tjg.twidget.ui.SeslSurfaceHighlight.markBrief(this, dp(28).toFloat())
        com.tjg.twidget.ui.SeslSurfaceHighlight.applyTo(this)
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
            addView(primaryText(format(previewHistory?.lastOrNull()?.followers ?: stats.followersCount), 22f, true).apply {
                setPadding(dp(20), 0, 0, 0)
            })
            val delta = previewHistory?.let { (it.lastOrNull()?.followers ?: 0) - (it.firstOrNull()?.followers ?: 0) }
                ?: TwidgetStore.followersDelta(this@TwidgetBriefActivity, username)
            if (delta != 0L) addView(primaryText(TwidgetStore.signedNumber(delta), 18f).apply {
                setPadding(dp(6), 0, 0, 0)
                setTextColor(getColor(if (delta < 0) R.color.metric_red else R.color.metric_green))
            })
        })
        val full = (previewHistory ?: TwidgetStore.fullHistory(this@TwidgetBriefActivity, username)).filter { it.followersKnown }
        val visible = (previewHistory ?: TwidgetStore.chartHistory(this@TwidgetBriefActivity, username, HistoryRange.WEEK))
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
            sourceAttribution = sourceAttribution)
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

    private fun topFollowersCard(card: BriefCard, previewFollowers: List<com.tjg.twidget.followers.TopFollower>? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.brief_card_background)
        com.tjg.twidget.ui.SeslSurfaceHighlight.markBrief(this, dp(28).toFloat())
        com.tjg.twidget.ui.SeslSurfaceHighlight.applyTo(this)
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
        val followers = (previewFollowers ?: TopFollowersStore.read(this@TwidgetBriefActivity, username).top).take(3)
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
                    addView(primaryText("", 20f, true).apply {
                        text = VerifiedBadge.decorate(
                            this@TwidgetBriefActivity,
                            follower.name.ifBlank { "@${follower.username}" },
                            follower.verified,
                            null,
                            dp(18),
                        )
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
        // A refresh can finish while cached content is still revealing. Keep that
        // presentation intact, then apply the latest data once every part settles.
        if (entranceInProgress) {
            pendingEntranceRender = snapshot to analytics
            return
        }
        renderedSnapshot = snapshot
        renderedTimingHour = java.time.ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0)
        applyContextBackground()
        visiblePresentation = BriefStore.presentation(this, username, snapshot)
        val summary = if (debugScenario == BriefDebugScenario.COMPONENTS)
            BriefEditorialSummary(getString(R.string.brief_preview_cards), getString(R.string.brief_preview_cards_summary))
        else visiblePresentation?.summary() ?: BriefEditorialSummary.from(snapshot, BriefStrings.from(this))
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
            .showStaticText(summary.title)
        findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body).showStaticText(summary.body)
        val columns = configureResponsiveLayout()
        val container = findViewById<LinearLayout>(R.id.brief_cards)
        findViewById<View>(R.id.brief_footer).visibility = if (debugScenario == BriefDebugScenario.COMPONENTS) View.GONE else View.VISIBLE
        container.removeAllViews()
        rankedTweetDescriptions.clear()
        // Keep every review preview independent so masonry can balance unequal tweet heights.
        val reviews = if (debugScenario == BriefDebugScenario.REAL) {
            BriefPostCoachSection.create(this, username, analytics,
                excludedPostUrls = snapshot.cards.mapNotNull { resolveBriefPost(it, analytics)?.url }.toSet())
        } else null
        refreshReviewPreviews = { reviews?.refreshDescriptions?.invoke(); refreshTweetRankDescriptions() }
        val sections = buildList {
            if (debugScenario == BriefDebugScenario.COMPONENTS) {
                addAll(BriefCardPreviewGallery.sections(this@TwidgetBriefActivity, snapshot,
                    card = { card -> cardSection(card, null) },
                    goal = { settings, metric -> milestoneCard(BriefCard("preview-goal", BriefCardType.MILESTONE, "", "", 0), settings to metric) },
                    followers = { topFollowersCard(BriefCard("preview-followers", BriefCardType.TOP_FOLLOWER, "", "", 0), it) },
                    history = ::followerChartCard,
                ).map { it to 20 })
                return@buildList
            }
            if (debugScenario == BriefDebugScenario.CHATGPT_LINK) {
                add(BriefChatGptLinkCard.create(this@TwidgetBriefActivity, preview = true) {} to 20)
            }
            if (debugScenario == BriefDebugScenario.REAL && BriefChatGptLinkCard.shouldShow(this@TwidgetBriefActivity)) {
                add(chatGptLoginCard() to 20)
            }
            val timingEnabled = BriefSettingsStore.contentEnabled(this@TwidgetBriefActivity, BriefContentCategory.POSTING_GUIDANCE)
            val timing = if (timingEnabled || intent.hasExtra("brief_posting_phase") && TwidgetStore.debugMenuUnlocked(this@TwidgetBriefActivity)) postingTimingCard(analytics) else null
            // Re-evaluate timing on display, rather than keeping yesterday's time-sensitive copy.
            val cards = snapshot.cards.filterNot { it.id.startsWith("posting-window-") }
            if (timing != null) add(cardSection(timing, analytics) to 20)
            cards.forEach { card -> add(cardSection(card, analytics) to 20) }
            reviews?.let { items ->
                items.previews.forEach { add(it to 20) }
                add(items.libraryCard to 20)
            }

        }.mapIndexed { index, (section, topMargin) ->
            section to if (index == 0) 32 else topMargin
        }
        refreshTweetRankDescriptions()
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
                ?: viewportWidth()
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

    override fun allowsPopOverPresentation(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        findViewById<View>(R.id.brief_root).post { reflowForViewport(force = true) }
    }

    private var renderedViewportWidth = 0

    private fun viewportWidth(): Int {
        val root = findViewById<View>(R.id.brief_root)
        val scroll = findViewById<View>(R.id.brief_scroll)
        return (scroll.width.takeIf { it > 0 } ?: (root.width - root.paddingLeft - root.paddingRight)
            .takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - scroll.paddingLeft - scroll.paddingRight
    }

    private fun reflowForViewport(force: Boolean = false) {
        val snapshot = renderedSnapshot ?: return
        if (!force && viewportWidth() == renderedViewportWidth) return
        // Old column translations and delayed entrance callbacks must not restore the previous width.
        entranceGeneration++
        entranceInProgress = false
        entranceStarted = false
        cardEntranceAnimations.forEach { it.cancel() }
        cardEntranceAnimations.clear()
        findViewById<View>(R.id.brief_footer).apply { animate().cancel(); alpha = 1f; translationY = 0f }
        val pending = pendingEntranceRender
        pendingEntranceRender = null
        render(pending?.first ?: snapshot, pending?.second ?: AnalyticsClient.cached(this, username))
        findViewById<androidx.core.widget.NestedScrollView>(R.id.brief_scroll).apply { scrollTo(0, scrollY) }
    }

    private fun configureResponsiveLayout(): Int {
        val availableWidth = viewportWidth().coerceAtLeast(1)
        renderedViewportWidth = availableWidth
        val columns = BriefLayoutPolicy.columnCount((availableWidth / resources.displayMetrics.density).toInt())
        findViewById<LinearLayout>(R.id.brief_content).layoutParams =
            (findViewById<LinearLayout>(R.id.brief_content).layoutParams as FrameLayout.LayoutParams).apply {
                width = if (columns == 1) {
                    FrameLayout.LayoutParams.MATCH_PARENT
                } else {
                    minOf(
                        availableWidth,
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
        val selectedPost = resolveBriefPost(card, analytics)
        val insight = card.type in setOf(BriefCardType.AUDIENCE_MOMENTUM, BriefCardType.CONVERSATION_PULSE,
            BriefCardType.CONTENT_PATTERN, BriefCardType.WEEKLY_RECAP, BriefCardType.TOPIC_SPOTLIGHT, BriefCardType.CONTENT_EXPERIMENT)
        val description = card.body.ifBlank { card.title }.let { text ->
            if (insight) text.substringBefore(". ").let { if (it.endsWith('.')) it else "$it." } else text
        }
        addView(sectionLabel(description).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            if (selectedPost != null) rankedTweetDescriptions += Triple(selectedPost, this, description)
        })
        val content = when (card.type) {
            BriefCardType.MILESTONE -> milestoneCard(card)
            BriefCardType.STREAK -> StreakCardFactory.createBrief(
                this@TwidgetBriefActivity,
                DailyStreakStore.snapshot(this@TwidgetBriefActivity, username),
            )
            BriefCardType.FOLLOWER_CONTEXT, BriefCardType.POST_CONTEXT -> contextStatCard(card)
            BriefCardType.CONTENT_EXPERIMENT,
            BriefCardType.TOPIC_SPOTLIGHT,
            BriefCardType.CONVERSATION_PULSE, BriefCardType.CONTENT_PATTERN,
            BriefCardType.AUDIENCE_MOMENTUM, BriefCardType.WEEKLY_RECAP ->
                if (card.chartPoints.isEmpty()) contextStatCard(card) else BriefInsightViews.create(this@TwidgetBriefActivity, card) { performCardAction(card) }
            BriefCardType.GROWTH, BriefCardType.SLOWDOWN -> followerChartCard()
            BriefCardType.POST -> selectedPost?.let { postCard(it) }
                ?: genericCard(card)
            BriefCardType.WORST_POST -> selectedPost?.let { postCard(it) }
                ?: genericCard(card)
            BriefCardType.POSTING_GUIDE -> compactGuideAction(card)
            BriefCardType.TOP_FOLLOWER -> topFollowersCard(card)
            BriefCardType.INACTIVITY -> genericCard(card, warm = true)
            else -> genericCard(card)
        }
        com.tjg.twidget.ui.SeslSurfaceHighlight.markBrief(content, dp(28).toFloat())
        com.tjg.twidget.ui.SeslSurfaceHighlight.applyTo(content)
        addView(content, matchWrap(top = 10))
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
                setImageResource(if (card.type in setOf(BriefCardType.FOLLOWER_CONTEXT, BriefCardType.AUDIENCE_MOMENTUM)) OneUiIconR.drawable.ic_oui_community else OneUiIconR.drawable.ic_oui_equalizer_2)
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
        minimumHeight = dp(52)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        setBackgroundResource(R.drawable.brief_guide_action_surface)
        clipToOutline = true
        applyBriefCardRipple()
        isClickable = card.action != BriefCardAction.NONE
        isFocusable = isClickable
        if (isClickable) setOnClickListener { performCardAction(card) }
        addView(primaryText(actionLabel(card) ?: card.title, 18f, bold = true))
    }

    private fun actionLabel(card: BriefCard): String? = when (card.action) {
        BriefCardAction.NONE -> null
        BriefCardAction.OPEN_SCHEDULER, BriefCardAction.COMPOSE_TWEET -> null
        BriefCardAction.OPEN_POST -> getString(R.string.brief_action_open_tweet)
    }

    private fun performCardAction(card: BriefCard) {
        when (card.action) {
            BriefCardAction.NONE -> Unit
            BriefCardAction.OPEN_SCHEDULER -> Unit

            BriefCardAction.COMPOSE_TWEET -> Unit
            BriefCardAction.OPEN_POST -> card.actionData
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?.let { com.tjg.twidget.ui.TweetPopOverLauncher.open(this, it) }
        }
    }

    private fun View.applyBriefCardRipple() {
        foreground = AppCompatResources.getDrawable(context, R.drawable.brief_card_ripple)
    }

    private fun milestoneCard(card: BriefCard, preview: Pair<com.tjg.twidget.main.AccountGoalSettings?, com.tjg.twidget.main.MilestoneMetricSnapshot?>? = null): View {
        val root = LayoutInflater.from(this).inflate(R.layout.milestone_card, null, false)
        val isSetup = if (preview != null) preview.first == null else card.actionData == BRIEF_MILESTONE_SETUP_ACTION
        val settings = if (preview != null) preview.first else if (isSetup) null else milestoneSettings(card)
        val metric = if (preview != null) preview.second else settings?.let {
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
        ).apply { alpha = Color.alpha(getColor(R.color.brief_card_surface)) }
        root.findViewById<MilestoneArcView>(R.id.milestone_arc).apply {
            this.progress = progress
            progressColor = accent
        }
        BriefGoalCardCopy.bind(this, root, username, settings, metric)
        if (debugScenario == BriefDebugScenario.REAL && settings != null) {
            com.tjg.twidget.main.MilestoneCelebration.attach(root, username, settings, metric?.value)
        }
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
        if (preview != null) {
            root.setOnClickListener(null)
            root.findViewById<ImageButton>(R.id.milestone_edit).visibility = View.GONE
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
        // Match the plain and tweet cards: tint the material without blocking the moving page background.
        colors = intArrayOf(start, end).map {
            androidx.core.graphics.ColorUtils.setAlphaComponent(it, Color.alpha(getColor(R.color.brief_card_surface)))
        }.toIntArray()
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
        if (localStatus == BriefLocalStatus.DOWNLOADABLE) {
            builder
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.brief_prepare_local) { _, _ -> downloadLocalModel() }
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
        if (entranceInProgress) return
        entranceInProgress = true
        entranceStarted = false
        entranceGeneration++
        cardEntranceAnimations.forEach { it.cancel() }
        cardEntranceAnimations.clear()
        val title = findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
        val body = findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body)
        title.prepareNowBriefTitleMotion()
        body.prepareNowBriefMotion()
        entranceSections.forEach { prepareEntranceView(it, CARD_LIFT_DP) }
        prepareEntranceView(findViewById(R.id.brief_footer), COPY_LIFT_DP)
    }

    private var backgroundMotion: ValueAnimator? = null
    private var backgroundMotionProgress = BriefBlueBackground.motionPhase()
    private var blueBackground: BriefBlueBackground? = null
    private var backgroundUnlockProgress = 1f
    private var backgroundUnlockStarted = false
    private var backgroundUnlockAnimator: ValueAnimator? = null

    private fun startBackgroundMotion() {
        if (backgroundMotion != null || !ValueAnimator.areAnimatorsEnabled()) return
        backgroundMotion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BriefBlueBackground.MOTION_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                backgroundMotionProgress = BriefBlueBackground.motionPhase()
                blueBackground?.setMotionProgress(backgroundMotionProgress)
            }
            start()
        }
    }

    private var renderedTimingHour: java.time.ZonedDateTime? = null
    private var appliedBlueTheme: Triple<BriefBlueTheme, Boolean, Float>? = null

    private fun applyContextBackground() {
        val preview = if (TwidgetStore.debugMenuUnlocked(this)) intent.getStringExtra("brief_blue_theme") else null
        val theme = BriefBlueTheme.entries.firstOrNull { it.name == preview } ?: BriefBlueTheme.current()
        val key = Triple(theme, isNight(), resources.displayMetrics.density)
        if (appliedBlueTheme != key) {
            appliedBlueTheme = key
            backgroundMotionProgress = BriefBlueBackground.motionPhase()
            blueBackground = BriefBlueBackground(theme, key.second, density = resources.displayMetrics.density).also {
                it.setMotionProgress(backgroundMotionProgress)
                it.setUnlockProgress(backgroundUnlockProgress)
            }
            findViewById<View>(R.id.brief_root).background = blueBackground
        }
    }

    private fun postingTimingCard(analytics: PostAnalytics?): BriefCard? {
        val preview = if (TwidgetStore.debugMenuUnlocked(this)) intent.getStringExtra("brief_posting_phase") else null
        val phase = BriefPostingPhase.entries.firstOrNull { it.name == preview }
        if (phase == null && debugScenario != BriefDebugScenario.REAL) return null
        val signal = if (phase != null) BriefPostingSignal(BriefPostingWindow.EVENING, 12, 1.7)
            else BriefPostingTiming.signal(analytics)
        signal ?: return null
        val hour = when (phase) {
            BriefPostingPhase.UPCOMING -> 9
            BriefPostingPhase.NOW -> 19
            BriefPostingPhase.NEXT_DAY -> 23
            null -> java.time.ZonedDateTime.now().hour
        }
        return BriefPostingTiming.card(signal, BriefStrings.from(this), hour)
    }

    private fun startBackgroundUnlock() {
        if (backgroundUnlockProgress >= 1f || backgroundUnlockAnimator != null) return
        backgroundUnlockStarted = true
        if (!ValueAnimator.areAnimatorsEnabled()) {
            backgroundUnlockProgress = 1f
            blueBackground?.setUnlockProgress(1f)
            return
        }
        backgroundUnlockAnimator = ValueAnimator.ofFloat(backgroundUnlockProgress, 1f).apply {
            duration = (BACKGROUND_TRANSITION_DURATION_MS * (1f - backgroundUnlockProgress)).toLong().coerceAtLeast(1L)
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                backgroundUnlockProgress = it.animatedValue as Float
                blueBackground?.setUnlockProgress(backgroundUnlockProgress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { backgroundUnlockAnimator = null }
            })
            start()
        }
    }

    private fun startPreparedBriefEntrance() {
        startBackgroundUnlock()
        if (!entranceInProgress || entranceStarted) return
        entranceStarted = true
        val generation = entranceGeneration
        findViewById<View>(R.id.brief_scroll).post {
            if (isFinishing || isDestroyed || generation != entranceGeneration) return@post
            val sections = entranceSections.toList()
            var remaining = sections.size + 3 // Heading, description, footer and each card.
            val finished = {
                if (generation == entranceGeneration && --remaining == 0) {
                    entranceInProgress = false
                    entranceStarted = false
                    val pending = pendingEntranceRender
                    pendingEntranceRender = null
                    pending?.let { (snapshot, analytics) -> render(snapshot, analytics) }
                }
            }
            findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_title)
                .startNowBriefTitleMotion(TITLE_DELAY_MS, onEnd = finished)
            findViewById<NowBriefSubtitleMotionView>(R.id.brief_summary_body)
                .startNowBriefMotion(COPY_DELAY_MS) {
                    sections.forEach { section ->
                        startNowBriefCardEntrance(section, 0L, finished)
                    }
                    finished()
                }
            startEntranceView(
                findViewById(R.id.brief_footer),
                FOOTER_DELAY_MS,
                COPY_DURATION_MS,
                PathInterpolator(0.2f, 0f, 0f, 1f),
                finished,
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
        onEnd: () -> Unit,
    ) {
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .withEndAction(onEnd)
            .start()
    }

    private fun startNowBriefCardEntrance(view: View, delay: Long, onEnd: () -> Unit) {
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
            cardEntranceAnimations += this
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) onEnd()
                }
            })
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
        typeface = TwidgetFonts.forApp(this@TwidgetBriefActivity, if (bold) 700 else 400)
        if (bold) TwidgetFonts.dashboardHeading(this)
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
        private const val BACKGROUND_TRANSITION_DURATION_MS = 1_800L
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
