package com.tjg.twidget.brief

import android.animation.ArgbEvaluator
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
import com.tjg.twidget.ui.MetricChartView
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.startRightSidePopOverActivity
import dev.oneuiproject.oneui.R as OneUiIconR
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TwidgetBriefActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var renderedSnapshot: BriefSnapshot? = null
    private var localStatus = BriefLocalStatus.UNAVAILABLE
    private var debugScenario: BriefDebugScenario = BriefDebugScenario.REAL
    private var reloadOnResume = false
    private var checkContentOnResume = false
    private var entranceSections: List<View> = emptyList()
    private var titleColorAnimator: ValueAnimator? = null
    private var backgroundAnimator: ValueAnimator? = null
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
        showLaunchShell()
        if (intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)) {
            prepareOnboardingBackgroundTransition()
        }
        afterFirstFrame(::loadInitialBrief)
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
        val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false) || contentChanged
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                if (!forceRefresh && debugScenario == BriefDebugScenario.REAL) {
                    BriefStore.read(this@TwidgetBriefActivity, username)?.let { snapshot ->
                        snapshot to AnalyticsClient.cached(this@TwidgetBriefActivity, username)
                    }
                } else {
                    null
                }
            }
            if (cached != null && cached.first.providerUsed != BriefProviderUsed.TEMPLATE) {
                render(cached.first, cached.second)
                prepareBriefEntrance()
                setLoading(false)
                startPreparedBriefEntrance()
                loadBrief(showSpinner = false, animateOnComplete = false)
            } else {
                loadBrief(
                    forceEngine = forceRefresh,
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
        findViewById<LottieAnimationView>(R.id.brief_loading).apply {
            visibility = View.GONE
            cancelAnimation()
        }
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
                forceAi = contentChanged,
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
                    forceAi = true,
                    showSpinner = true,
                    animateOnComplete = true,
                    clearContentPendingOnSuccess = true,
                )
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
        if (showSpinner) setLoading(true)
        lifecycleScope.launch {
            var entrancePrepared = false
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
                        BriefEngine.rebuild(
                            this@TwidgetBriefActivity,
                            username,
                            force = forceEngine,
                        ),
                    )
                }
                val result = if (debugScenario == BriefDebugScenario.REAL) {
                    BriefAiCoordinator.enrich(
                        this@TwidgetBriefActivity,
                        source,
                        force = forceAi,
                    )
                } else {
                    BriefAiResult(source, BriefLocalStatus.UNAVAILABLE)
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
                if (showSpinner) setLoading(false)
                if (entrancePrepared) startPreparedBriefEntrance()
            }
        }
    }

    override fun onDestroy() {
        apiKeyDialog?.dismiss()
        apiKeyDialog = null
        titleColorAnimator?.cancel()
        titleColorAnimator = null
        backgroundAnimator?.cancel()
        backgroundAnimator = null
        super.onDestroy()
    }

    private fun setLoading(loading: Boolean) {
        findViewById<View>(R.id.brief_scroll).visibility =
            if (!loading && !providerSetupRequired) View.VISIBLE else View.GONE
        findViewById<View>(R.id.brief_provider_required).visibility =
            if (!loading && providerSetupRequired) View.VISIBLE else View.GONE
        findViewById<LottieAnimationView>(R.id.brief_loading).apply {
            visibility = if (loading) View.VISIBLE else View.GONE
            if (loading) playAnimation() else cancelAnimation()
        }
        val contentEnabled = !loading && !providerSetupRequired
        findViewById<ImageButton>(R.id.brief_footer_info).isEnabled = contentEnabled
        findViewById<ImageButton>(R.id.brief_footer_settings).isEnabled = contentEnabled
    }

    private fun bindChrome() {
        applyEdgeToEdgeInsets(findViewById(R.id.brief_root))
        val tint = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
        findViewById<ImageButton>(R.id.brief_back).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, OneUiIconR.drawable.ic_oui_back))
            imageTintList = tint
            setOnClickListener { finish() }
        }
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
        val required = debugScenario == BriefDebugScenario.REAL && briefRequiresApiKey(
            result.localStatus,
            BriefSettingsStore.cloudApiKey(this),
        )
        providerSetupRequired = required
        if (!required) return false

        setLoading(false)
        if (intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false) && !apiKeyPromptShown) {
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

    private fun postCard(post: PostSummary, sourceAttribution: String = ""): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundResource(R.drawable.brief_card_background)
        isClickable = post.url.isNotBlank()
        isFocusable = isClickable
        if (isClickable) {
            applyBriefCardRipple()
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(post.url)))
            }
        }

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(ImageView(context).apply {
                ProfileImageLoader.loadInto(context, this, post.authorAvatar)
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(LinearLayout(this@TwidgetBriefActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(primaryText(post.authorName.ifBlank { TwidgetStore.currentStats(context, username).fullName }, 14f, true))
                val byline = buildString {
                    append("@${post.authorUserName.ifBlank { username }} · ${postDate(post)}")
                    sourceAttribution.takeIf(String::isNotBlank)?.let { append(" · $it") }
                }
                addView(supportingText(byline, 12f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        addView(primaryText(post.text.ifBlank { post.url }, 14f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrap(top = 10))

        post.media.firstOrNull()?.let { media ->
            addView(ImageView(context).apply {
                contentDescription = media.alt.ifBlank { getString(R.string.post_media) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                ProfileImageLoader.loadMediaInto(context, this, media.url, dp(14))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(218),
            ).apply { topMargin = dp(10) })
        }

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addMetric(this, OneUiIconR.drawable.ic_oui_equalizer, post.engagements, "Engagements")
            addMetric(this, OneUiIconR.drawable.ic_oui_equalizer_2, post.views, "Impressions")
            addMetric(this, OneUiIconR.drawable.ic_oui_heart_outline, post.likes, "Likes")
            addMetric(this, OneUiIconR.drawable.ic_oui_message_outline, post.quotes, "Quote tweets")
            addMetric(this, OneUiIconR.drawable.ic_oui_repeat, post.reposts, "Retweets")
        }, matchWrap(top = 10))
    }

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
        addView(primaryText(card.body, 14f).apply {
            setPadding(dp(20), dp(16), dp(20), dp(10))
        })
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
        val summary = BriefEditorialSummary.from(snapshot)
        findViewById<TextView>(R.id.brief_summary_title).text = summary.title
        findViewById<TextView>(R.id.brief_summary_body).text = summary.body
        val columns = configureResponsiveLayout()
        val container = findViewById<LinearLayout>(R.id.brief_cards)
        container.removeAllViews()
        val sections = buildList {
            if (snapshot.upcomingTweets.isNotEmpty()) {
                add(upcomingSection(snapshot.upcomingTweets) to 24)
            }
            snapshot.cards.forEach { card -> add(cardSection(card, analytics) to 20) }
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
        addView(sectionLabel("Upcoming scheduled tweets"))
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
            tweet.preview.ifBlank { "Scheduled tweet" }
        }, 14f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrap(top = 10))

        firstPost?.media?.firstOrNull()?.let { media ->
            addView(ImageView(context).apply {
                contentDescription = getString(R.string.post_media)
                scaleType = ImageView.ScaleType.CENTER_CROP
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
                dp(218),
            ).apply { topMargin = dp(10) })
        }

        val details = buildList {
            if (tweet.threadCount > 1) add("${tweet.threadCount}-tweet thread")
            if (tweet.mediaCount > 0) add("${tweet.mediaCount} media")
            add(if (tweet.provider == ScheduleProvider.BUFFER) "Buffer" else "Local reminder")
        }.joinToString(" · ")
        addView(supportingText(details, 12f), matchWrap(top = 10))
    }

    private fun draftPostCard(post: ScheduledPost): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        isClickable = true
        isFocusable = true
        setBackgroundResource(R.drawable.brief_card_background)
        applyBriefCardRipple()
        setOnClickListener { openDraft(post) }

        val stats = TwidgetStore.currentStats(this@TwidgetBriefActivity, username)
        val firstTweet = post.thread.firstOrNull()
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
                val source = if (post.provider == ScheduleProvider.BUFFER) "Buffer draft" else "Local draft"
                addView(supportingText("@$username · $source", 12f).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        addView(primaryText(firstTweet?.text.orEmpty().ifBlank { getString(R.string.brief_draft_without_text) }, 14f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrap(top = 10))

        firstTweet?.media
            ?.firstOrNull { media -> media.mimeType?.startsWith("image/", ignoreCase = true) != false }
            ?.let { media ->
            addView(ImageView(context).apply {
                contentDescription = getString(R.string.post_media)
                scaleType = ImageView.ScaleType.CENTER_CROP
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
                dp(218),
            ).apply { topMargin = dp(10) })
        }

        val threadCount = post.thread.size
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            contentDescription = resources.getQuantityString(
                R.plurals.brief_draft_thread_count,
                threadCount,
                threadCount,
            )
            addView(ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(OneUiIconR.drawable.ic_oui_list)
                imageTintList = ColorStateList.valueOf(getColor(R.color.oneui_text_primary))
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(primaryText(
                resources.getQuantityString(
                    R.plurals.brief_draft_thread_count,
                    threadCount,
                    threadCount,
                ),
                14f,
            ).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(4) })
        }, matchWrap(top = 10))
    }

    private fun scheduleDate(timestamp: Long): String = if (timestamp > 0L) {
        AppLocales.formatDate(timestamp, "EEE, d. MMM · HH:mm", "EEE, MMM d · h:mm a")
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
        val hasFetchedPost = when (card.type) {
            BriefCardType.POST -> analytics?.best != null
            BriefCardType.WORST_POST -> analytics?.worst != null
            BriefCardType.POST_FOLLOW_THROUGH -> followThroughPost != null
            else -> false
        }
        val explanationAsHeading = hasFetchedPost || card.type in setOf(
            BriefCardType.SCHEDULE_GUIDE,
            BriefCardType.POSTING_GUIDE,
            BriefCardType.GROWTH,
            BriefCardType.SLOWDOWN,
        )
        if (explanationAsHeading) {
            addView(sectionLabel(card.body).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        } else {
            addView(sectionLabel(sectionHeading(card)))
        }
        val content = when (card.type) {
            BriefCardType.MILESTONE -> milestoneCard(card)
            BriefCardType.STREAK -> StreakCardFactory.create(
                this@TwidgetBriefActivity,
                DailyStreakStore.snapshot(this@TwidgetBriefActivity, username),
                titleOverride = card.title,
                detailOverride = card.body,
            )
            BriefCardType.GROWTH, BriefCardType.SLOWDOWN -> followerChartCard()
            BriefCardType.POST -> analytics?.best?.let { postCard(it) }
                ?: genericCard(card)
            BriefCardType.WORST_POST -> analytics?.worst?.let { postCard(it) }
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

    private fun sectionHeading(card: BriefCard): String = when (card.type) {
        BriefCardType.MILESTONE -> {
            if (card.actionData == BRIEF_MILESTONE_SETUP_ACTION) {
                card.title
            } else {
                val settings = milestoneSettings(card)
                val stats = TwidgetStore.currentStats(this, username)
                val metric = MilestoneMetricResolver.resolve(
                    context = this,
                    account = username,
                    metric = settings.metric,
                    stats = stats,
                    history = TwidgetStore.fullHistory(this, username),
                    analytics = AnalyticsClient.cached(this, username),
                    imported = ImportedAnalyticsStore.all(this, username),
                )
                val progress = metric.value?.let {
                    MilestonePolicy.progress(it, settings.target)
                } ?: 0
                when {
                    progress >= 100 -> getString(
                        R.string.brief_goal_reached_heading,
                        settings.metric.goalNoun,
                    )
                    progress >= 75 -> "You’re $progress% to your goal"
                    else -> getString(R.string.brief_goal_heading, settings.metric.goalNoun)
                }
            }
        }
        BriefCardType.STREAK -> getString(R.string.brief_streak_heading)
        else -> card.title
    }

    private fun genericCard(card: BriefCard, warm: Boolean = false): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(19), dp(16), dp(19), dp(16))
        background = if (warm) radialCard(
            Color.rgb(255, 189, 157),
            getColor(R.color.oneui_card_bg),
        ) else AppCompatResources.getDrawable(context, R.drawable.brief_card_background)
        addView(primaryText(card.body, 14f))
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
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundResource(R.drawable.brief_guide_action_surface)
            clipToOutline = true
            applyBriefCardRipple()
            isClickable = card.action != BriefCardAction.NONE
            isFocusable = isClickable
            if (isClickable) setOnClickListener(openPrimary)
            addView(primaryText(actionLabel(card) ?: card.title, 18f, bold = true))
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(10)
        })

        addView(ImageView(context).apply {
            setImageResource(OneUiIconR.drawable.ic_oui_open)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundResource(R.drawable.brief_guide_action_button)
            clipToOutline = true
            applyBriefCardRipple()
            contentDescription = "Open scheduled tweets"
            isClickable = true
            isFocusable = true
            setOnClickListener(openSchedule)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
    }

    private fun actionLabel(card: BriefCard): String? = when (card.action) {
        BriefCardAction.NONE -> null
        BriefCardAction.OPEN_SCHEDULER -> "Review schedule"
        BriefCardAction.COMPOSE_TWEET -> "Draft a tweet"
        BriefCardAction.OPEN_POST -> "Open tweet"
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
        root.findViewById<TextView>(R.id.milestone_title).text = card.title
        root.findViewById<TextView>(R.id.milestone_message).text = card.body
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
        root.contentDescription = "${card.title}. ${card.body}"
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
        val snapshot = renderedSnapshot ?: return
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.brief_ai_disclaimer)
            .setMessage(getString(R.string.brief_ai_disclaimer_body, snapshot.providerMessage))
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
        titleColorAnimator?.cancel()
        val title = findViewById<TextView>(R.id.brief_summary_title)
        val body = findViewById<TextView>(R.id.brief_summary_body)
        prepareEntranceView(title, TITLE_LIFT_DP)
        title.setTextColor(getColor(R.color.oneui_accent))
        prepareEntranceView(body, COPY_LIFT_DP)
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
            val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            val title = findViewById<TextView>(R.id.brief_summary_title)
            startEntranceView(title, TITLE_DELAY_MS, TITLE_DURATION_MS, interpolator)
            titleColorAnimator = ValueAnimator.ofArgb(
                getColor(R.color.oneui_accent),
                getColor(R.color.oneui_text_primary),
            ).apply {
                startDelay = TITLE_DELAY_MS
                duration = TITLE_COLOR_DURATION_MS
                addUpdateListener { animator ->
                    title.setTextColor(animator.animatedValue as Int)
                }
                start()
            }
            startEntranceView(
                findViewById(R.id.brief_summary_body),
                COPY_DELAY_MS,
                COPY_DURATION_MS,
                interpolator,
            )
            entranceSections.forEachIndexed { index, section ->
                startEntranceView(
                    section,
                    CARD_DELAY_MS + minOf(index, MAX_STAGGERED_CARD_INDEX) * CARD_STAGGER_MS,
                    CARD_DURATION_MS,
                    interpolator,
                )
            }
            startEntranceView(
                findViewById(R.id.brief_footer),
                FOOTER_DELAY_MS,
                COPY_DURATION_MS,
                interpolator,
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

    private fun sectionLabel(value: String) = primaryText(value, 14f).apply {
        setPadding(dp(19), 0, dp(19), 0)
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
        AppLocales.formatDate(post.timestamp, "d. MMM, HH:mm", "MMM d, h:mm a")
    } else post.createdAt

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun format(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    companion object {
        private const val MASONRY_COLUMN_GAP_DP = 20
        private const val TITLE_LIFT_DP = 10
        private const val COPY_LIFT_DP = 12
        private const val CARD_LIFT_DP = 18
        private const val TITLE_DELAY_MS = 35L
        private const val COPY_DELAY_MS = 115L
        private const val CARD_DELAY_MS = 190L
        private const val CARD_STAGGER_MS = 58L
        private const val FOOTER_DELAY_MS = 420L
        private const val TITLE_DURATION_MS = 460L
        private const val TITLE_COLOR_DURATION_MS = 620L
        private const val COPY_DURATION_MS = 400L
        private const val CARD_DURATION_MS = 440L
        private const val MAX_STAGGERED_CARD_INDEX = 7
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
