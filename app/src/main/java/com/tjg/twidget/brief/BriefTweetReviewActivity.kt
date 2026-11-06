package com.tjg.twidget.brief

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.widget.NestedScrollView
import com.tjg.twidget.R
import com.tjg.twidget.analytics.PostSummary
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.startRightSidePopOverActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import dev.oneuiproject.oneui.R as OneUiIconR

class BriefTweetReviewActivity : FoldablePopOverActivity() {
    private lateinit var target: BriefReviewTarget
    private lateinit var session: BriefReviewSession
    private lateinit var screen: BriefReviewScreen
    private var keyDialog: AlertDialog? = null
    private var forceFirstOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = BriefReviewTarget(intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
            intent.getStringExtra(EXTRA_DRAFT), intent.getStringExtra(EXTRA_POST), intent.getBooleanExtra(EXTRA_DASHBOARD, false))
        if (target.username.isBlank() || target.postUrl == null || target.draftId != null) { finish(); return }
        forceFirstOpen = savedInstanceState == null && intent.getBooleanExtra(EXTRA_DEBUG_FORCE, false) && TwidgetStore.debugMenuUnlocked(this)
        setContentView(R.layout.activity_brief_tweet_review)
        applyEdgeToEdgeInsets(findViewById(R.id.review_root)) { bottom ->
            BriefPageChrome.navigationInset(findViewById(R.id.review_scroll), bottom)
        }
        findViewById<android.view.View>(R.id.review_root).addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) configureWidth()
        }
        val toolbar = BriefPageChrome.review(findViewById(R.id.review_root),
            if (target.draftId != null) R.string.review_draft_title else R.string.review_title) { onBackPressedDispatcher.onBackPressed() }
        session = ViewModelProvider(this)[BriefReviewSession::class.java]
        screen = BriefReviewScreen(this, BriefReviewCallbacks(
            retry = { session.open(target, force = true) },
            info = { AlertDialog.Builder(this).setTitle(R.string.review_stat_info_title)
                .setMessage(R.string.review_stat_info_body).setPositiveButton(android.R.string.ok, null).show() },
            addKey = ::addKey,
            openPost = ::openPost,
            changeMode = ::changeMode,
            limits = { body -> AlertDialog.Builder(this).setTitle(R.string.coach_limits)
                .setMessage(body).setPositiveButton(android.R.string.ok, null).show() },
        ))
        findViewById<LinearLayout>(R.id.review_content).addView(screen.view)
        configureWidth()
        val appBar = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.review_app_bar)
        if (savedInstanceState == null) appBar.setExpanded(false, false)
        val scroll = findViewById<NestedScrollView>(R.id.review_scroll)
        val refresh = findViewById<SwipeRefreshLayout>(R.id.review_refresh).apply {
            isEnabled = true
            setOnChildScrollUpCallback { _, _ ->
                appBar.top < 0 || scroll.canScrollVertically(-1)
            }
            setOnRefreshListener { isRefreshing = false; session.open(target, force = true) }
        }
        val refreshAction = toolbar.menu.add(android.view.Menu.NONE, R.id.review_refresh_action, android.view.Menu.NONE, R.string.review_refresh).apply {
            setIcon(OneUiIconR.drawable.ic_oui_refresh)
            icon?.setTint(getColor(R.color.oneui_text_primary))
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { session.open(target, force = true); true }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.state.collect {
                    screen.render(it)
                    // The page has its own AI spinner; finish the pull indicator once handled.
                    refresh.isRefreshing = false
                    refreshAction.isEnabled = it.phase != BriefReviewPhase.GENERATING && it.phase != BriefReviewPhase.LOADING
                } }
                launch { while (true) { delay(60_000); session.open(target) } }
            }
        }
    }

    private fun changeMode() {
        AlertDialog.Builder(this).setTitle(R.string.review_change_mode)
            .setMessage(R.string.review_mode_explainer)
            .setPositiveButton(R.string.brief_provider_auto_short) { _, _ -> selectMode(BriefProviderMode.AUTO) }
            .setNeutralButton(R.string.brief_provider_cloud) { _, _ -> selectMode(BriefProviderMode.CLOUD) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun selectMode(mode: BriefProviderMode) {
        BriefSettingsStore.setProvider(this, mode)
        session.open(target, force = true)
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized) {
            session.open(target, force = forceFirstOpen)
            forceFirstOpen = false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::screen.isInitialized) configureWidth()
    }

    private fun configureWidth() {
        val root = findViewById<android.view.View>(R.id.review_root)
        val available = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val width = minOf((available - root.paddingLeft - root.paddingRight).coerceAtLeast(1), BriefReviewViews.dp(this, 600))
        findViewById<android.view.View>(R.id.review_content).apply { layoutParams = layoutParams.apply { this.width = width } }
    }

    private fun addKey() {
        keyDialog?.dismiss()
        keyDialog = BriefApiKeyDialog.show(this, required = true) {
            session.open(target, force = true)
        }
    }

    private fun openPost(post: PostSummary) {
        BriefReviewTweetCard.openTweet(this, post.url)
    }



    override fun onDestroy() {
        keyDialog?.dismiss()
        if (::screen.isInitialized) screen.dispose()
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_DEBUG_FORCE = "review_debug_force"
        private const val EXTRA_USERNAME = "review_username"
        private const val EXTRA_DRAFT = "review_draft_id"
        private const val EXTRA_POST = "review_post_url"
        private const val EXTRA_DASHBOARD = "review_from_dashboard"
        internal fun intent(context: Context, target: BriefReviewTarget): Intent = Intent(context, BriefTweetReviewActivity::class.java)
            .putExtra(EXTRA_USERNAME, target.username).apply {
                target.draftId?.let { putExtra(EXTRA_DRAFT, it) }
                target.postUrl?.let { putExtra(EXTRA_POST, it) }
                putExtra(EXTRA_DASHBOARD, target.fromDashboard)
            }
    }
}
