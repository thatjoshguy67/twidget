package com.tjg.twidget.main

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.analytics.PostAnalytics
import com.tjg.twidget.analytics.XAnalyticsMovement
import com.tjg.twidget.banger.BangerScanWorker
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.env.BuildVars
import com.tjg.twidget.followers.TopFollowersBridgeSyncWorker
import com.tjg.twidget.notices.NoticeBadgeDrawable
import com.tjg.twidget.notices.NoticesActivity
import com.tjg.twidget.notices.ReleaseNoticesStore
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.ui.startRightSidePopOverActivity
import com.tjg.twidget.update.AppUpdateManager
import com.tjg.twidget.update.UpdateCheckWorker
import com.tjg.twidget.update.UpdateNotificationHelper
import com.tjg.twidget.widget.RefreshWorker
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.R as OneUiIconR

class MainActivity : EdgeToEdgeActivity() {
    internal val dashboardTweetLauncher = DashboardTweetLauncher(this)

    internal var accounts = emptyList<String>()
    internal var selectedAccount: String = ""
    internal var analytics: PostAnalytics? = null
    internal var importedAnalytics = emptyList<XAnalyticsMovement>()

    internal lateinit var dashboardBinder: MainDashboardBinder
    internal lateinit var drawerController: MainDrawerController
    internal lateinit var syncController: MainSyncController
    internal lateinit var postAnalyticsBinder: MainPostAnalyticsBinder
    internal lateinit var editModeController: MainEditModeController
    private var skipNextResumeRender = false
    private var launchChromeReady = false

    private val bangerUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val username = intent?.getStringExtra(BangerScanWorker.EXTRA_USERNAME) ?: return
            if (!username.equals(selectedAccount, ignoreCase = true) || isFinishing || isDestroyed) return
            analytics = AnalyticsClient.cached(this@MainActivity, selectedAccount)
            dashboardBinder.bindContent()
        }
    }

    private val briefUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val account = intent?.getStringExtra(com.tjg.twidget.brief.BriefStore.EXTRA_USERNAME) ?: return
            if (!account.equals(selectedAccount, true) || isFinishing || isDestroyed ||
                editModeController.editMode) return
            dashboardBinder.bindContent(refreshRemote = false)
        }
    }

    private val topFollowersUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val username = intent?.getStringExtra(TopFollowersBridgeSyncWorker.EXTRA_USERNAME) ?: return
            if (!username.equals(selectedAccount, ignoreCase = true) || isFinishing || isDestroyed) return
            dashboardBinder.bindContent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TwidgetStore.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        editModeController = MainEditModeController(this)
        dashboardBinder = MainDashboardBinder(this)
        drawerController = MainDrawerController(
            activity = this,
            drawerLayoutId = R.id.main_toolbar_layout,
            drawerNavigationId = R.id.drawer_nav,
            accounts = { accounts },
            selectedAccount = { selectedAccount },
            onAccountSelected = { account ->
                selectedAccount = account
                render()
            },
            isEditMode = { editModeController.editMode },
            exitEditMode = { editModeController.setEditMode(false) },
        )
        syncController = MainSyncController(this)
        postAnalyticsBinder = MainPostAnalyticsBinder(this)

        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.main_toolbar_layout)
            .setExpanded(expanded = false, animate = false)
        accounts = TwidgetStore.accounts(this)
        selectedAccount = savedInstanceState?.getString(STATE_ACCOUNT)
            ?.takeIf { saved -> accounts.any { it.equals(saved, ignoreCase = true) } }
            ?: accounts.firstOrNull().orEmpty()
        if (selectedAccount.isNotBlank()) {
            drawerController.renderHeader()
        }
        setupLaunchChrome()
        applyEdgeToEdgeInsets(findViewById(R.id.dashboard_root))
        onBackPressedDispatcher.addCallback(this, editModeController.exitEditModeOnBack)
        // Bind the saved dashboard before its first frame. Remote refresh and
        // maintenance begin afterwards without replacing the cached cards.
        render(refreshRemote = false)
        skipNextResumeRender = true
        val decor = window.decorView
        decor.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var scheduled = false

            override fun onDraw() {
                if (scheduled) return
                scheduled = true
                decor.post {
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnDrawListener(this)
                    }
                    if (isFinishing || isDestroyed) return@post
                    setupLaunchChrome()
                    AppExecutors.execute {
                        TwidgetStore.migrateStoredHistories(applicationContext)
                        RefreshWorker.schedule(applicationContext)
                        UpdateCheckWorker.schedule(applicationContext)
                    }
                    syncController.maybeRefreshAnalytics(selectedAccount)
                    syncController.maybeRefreshStreak(selectedAccount)
                    if (savedInstanceState == null) checkReleasesOnLaunch()
                    if (savedInstanceState == null && TwidgetStore.settings(this@MainActivity).refreshOnLaunch) {
                        syncController.sync(showProgress = false)
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeRender) {
            skipNextResumeRender = false
        } else if (launchChromeReady) {
            render()
        }
        updateSettingsBadge()
        invalidateOptionsMenu()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, briefUpdateReceiver,
            IntentFilter(com.tjg.twidget.brief.BriefStore.ACTION_PRESENTATION_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(
            this,
            bangerUpdateReceiver,
            IntentFilter(BangerScanWorker.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            topFollowersUpdateReceiver,
            IntentFilter(TopFollowersBridgeSyncWorker.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        editModeController.finishDashboardDrag(commit = false)
        runCatching { unregisterReceiver(briefUpdateReceiver) }
        runCatching { unregisterReceiver(bangerUpdateReceiver) }
        runCatching { unregisterReceiver(topFollowersUpdateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        if (::dashboardBinder.isInitialized) dashboardBinder.dispose()
        // Invalidates every queued UI delivery owned by this Activity. The
        // background result may still populate application caches, but can no
        // longer retain or repaint a destroyed window.
        if (::syncController.isInitialized) {
            syncController.invalidateLifecycle()
        }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        updateNoticesMenuIcon(menu)
        return true
    }

    internal fun requestTopFollowersNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9042)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_open_profile)?.let { item ->
            val logo = com.tjg.twidget.ui.AppAppearance.logoDrawable(this)
            if (item !== preparedProfileItem || logo != preparedProfileLogo) {
                item.setIcon(logo)
                preparedProfileItem = item
                preparedProfileLogo = logo
            }
        }
        // Set each item's final state once. Temporarily exposing the edit-only
        // action makes the floating toolbar relayout as its overflow popup opens.
        menu.findItem(R.id.menu_notices)?.isVisible = !editModeController.editMode
        updateNoticesMenuIcon(menu)
        menu.findItem(R.id.menu_add_widget)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_open_profile)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_edit_layout)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_reset_layout)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_add_card)?.isVisible = editModeController.editMode
        menu.findItem(R.id.menu_done_editing)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        when (item.itemId) {
            R.id.menu_notices -> {
                ReleaseNoticesStore.markCurrentAsSeen(this)
                invalidateOptionsMenu()
                startRightSidePopOverActivity(Intent(this, NoticesActivity::class.java))
            }
            R.id.menu_add_widget -> requestWidgetPin()
            R.id.menu_open_profile -> openActiveProfile()
            R.id.menu_edit_layout -> editModeController.setEditMode(true)
            R.id.menu_reset_layout -> editModeController.confirmResetLayout()
            R.id.menu_add_card -> editModeController.showAddCardDialog()
            R.id.menu_done_editing -> editModeController.setEditMode(false)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ACCOUNT, selectedAccount)
        super.onSaveInstanceState(outState)
    }

    // Show one native update dot. One UI moves it from the collapsed drawer
    // affordance to the settings button when the drawer is expanded.
    private fun updateSettingsBadge() {
        if (::drawerController.isInitialized) drawerController.updateNavigationBadge()
    }

    private fun checkReleasesOnLaunch() {
        if (!BuildVars.IN_APP_UPDATES) return
        val appContext = applicationContext
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        val channel = AboutActivity.savedUpdateChannel(this)
        val lifecycleToken = syncController.lifecycleGeneration
        AppExecutors.execute {
            val result = runCatching {
                AppUpdateManager.checkReleases(installedVersion, channel)
            }
            result.onSuccess { check ->
                TwidgetStore.setUpdateAvailable(
                    appContext,
                    check.update != null,
                    check.update?.version?.toString(),
                )
                check.update?.let { UpdateNotificationHelper.showIfNeeded(appContext, it) }
                    ?: UpdateNotificationHelper.cancel(appContext)
                // The debug channel uses a quota-free release sidecar and does
                // not refresh notices through the rate-limited GitHub API.
                if (check.notices.isNotEmpty()) ReleaseNoticesStore.save(appContext, check.notices)
            }
            syncController.postUiIfCurrent(lifecycleToken) {
                updateSettingsBadge()
                invalidateOptionsMenu()
            }
        }
    }

    private fun updateNoticesMenuIcon(menu: Menu) {
        val item = menu.findItem(R.id.menu_notices) ?: return
        val unseen = ReleaseNoticesStore.hasUnseen(this)
        if (item === preparedNoticesItem && unseen == preparedNoticesUnseen && item.icon != null) return
        val base = AppCompatResources.getDrawable(this, OneUiIconR.drawable.ic_oui_notice_outline) ?: return
        item.icon = if (unseen) {
            NoticeBadgeDrawable(base, getColor(R.color.notice_badge_orange), resources.displayMetrics.density)
        } else {
            base
        }
        preparedNoticesItem = item
        preparedNoticesUnseen = unseen
    }

    private var preparedProfileItem: MenuItem? = null
    private var preparedProfileLogo: Int? = null
    private var preparedNoticesItem: MenuItem? = null
    private var preparedNoticesUnseen: Boolean? = null

    internal fun render(bindDashboard: Boolean = true, refreshRemote: Boolean = true) {
        accounts = TwidgetStore.accounts(this)
            .ifEmpty { listOf(TwidgetStore.settings(this).username) }
            .filter { it.isNotBlank() }
        if (accounts.isEmpty()) return
        if (selectedAccount.isBlank() || accounts.none { it.equals(selectedAccount, ignoreCase = true) }) {
            selectedAccount = accounts.first()
        }
        val dashboard = findViewById<SwipeRefreshLayout>(R.id.main_refresh)
        dashboard.visibility = View.VISIBLE
        dashboard.isEnabled = true
        if (bindDashboard) dashboardBinder.bindContent(refreshRemote)
        drawerController.buildDrawer()
        drawerController.renderHeader()
    }

    private fun setupLaunchChrome() {
        if (launchChromeReady) return
        syncController.setupRefresh()
        drawerController.setupDrawerChrome()
        launchChromeReady = true
    }

    private fun openActiveProfile() {
        val username = selectedAccount.ifBlank { TwidgetStore.settings(this).username }
            .trim()
            .trimStart('@')
        if (username.isBlank()) return

        val encoded = Uri.encode(username)
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("twitter://user?screen_name=$encoded"))
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/$encoded"))
        try {
            startActivity(appIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(webIntent)
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

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        private const val STATE_ACCOUNT = "main_account"
    }
}
