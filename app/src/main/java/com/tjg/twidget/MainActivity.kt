package com.tjg.twidget

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.oneuiproject.oneui.layout.Badge
import dev.oneuiproject.oneui.layout.DrawerLayout
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.R as OneUiIconR

class MainActivity : ScheduleQueueHostActivity() {
    override val embedsScheduleQueue: Boolean = true

    private var destination = MainDestination.DASHBOARD
    private lateinit var scheduleBackCallback: androidx.activity.OnBackPressedCallback
    internal var accounts = emptyList<String>()
    internal var selectedAccount: String = ""
    internal var analytics: PostAnalytics? = null
    internal var importedAnalytics = emptyList<XAnalyticsMovement>()

    internal lateinit var dashboardBinder: MainDashboardBinder
    internal lateinit var drawerController: MainDrawerController
    internal lateinit var syncController: MainSyncController
    internal lateinit var postAnalyticsBinder: MainPostAnalyticsBinder
    internal lateinit var editModeController: MainEditModeController

    private val bangerUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val username = intent?.getStringExtra(BangerScanWorker.EXTRA_USERNAME) ?: return
            if (!username.equals(selectedAccount, ignoreCase = true) || isFinishing || isDestroyed) return
            analytics = AnalyticsClient.cached(this@MainActivity, selectedAccount)
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
                destination = MainDestination.DASHBOARD
                selectedAccount = account
                render()
            },
            isEditMode = { editModeController.editMode },
            isSchedulePage = { destination == MainDestination.SCHEDULING },
            openSchedule = ::showScheduling,
            openScheduleTrash = ::openScheduleTrash,
        )
        syncController = MainSyncController(this)
        postAnalyticsBinder = MainPostAnalyticsBinder(this)

        setContentView(R.layout.activity_main)
        attachEmbeddedScheduleQueue(
            toolbar = findViewById<ToolbarLayout>(R.id.main_toolbar_layout),
            root = findViewById(R.id.schedule_queue_container),
        )
        destination = savedInstanceState?.getString(STATE_DESTINATION)
            ?.let(MainDestination::valueOf)
            ?: MainDestination.DASHBOARD
        val scheduleFab = findViewById<View>(R.id.schedule_fab)
        applyEdgeToEdgeInsets(findViewById(R.id.main_toolbar_layout)) { navigationBarInset ->
            scheduleFab.updateBottomMarginForNavigationBar(dp(20), navigationBarInset)
            updateScheduleBottomInsets(navigationBarInset)
        }
        onBackPressedDispatcher.addCallback(this, editModeController.exitEditModeOnBack)
        scheduleBackCallback = object : androidx.activity.OnBackPressedCallback(
            destination == MainDestination.SCHEDULING,
        ) {
            override fun handleOnBackPressed() {
                if (handleEmbeddedScheduleBack()) return
                showDashboard()
                isEnabled = false
            }
        }
        onBackPressedDispatcher.addCallback(this, scheduleBackCallback)
        RefreshWorker.schedule(this)
        TwidgetStore.migrateStoredHistories(this)
        syncController.setupRefresh()
        drawerController.setupDrawerChrome()
        setupScheduleAction()
        render()
        if (savedInstanceState == null) checkReleasesOnLaunch()
        if (TwidgetStore.settings(this).refreshOnLaunch) {
            syncController.sync()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        updateSettingsBadge()
        invalidateOptionsMenu()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            bangerUpdateReceiver,
            IntentFilter(BangerScanWorker.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(bangerUpdateReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
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
        menuInflater.inflate(R.menu.schedule, menu)
        updateNoticesMenuIcon(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (destination == MainDestination.SCHEDULING) {
            setDashboardMenuVisible(menu, false)
            return super.onPrepareOptionsMenu(menu)
        }
        menu.findItem(R.id.schedule_trash_menu)?.isVisible = false
        menu.setGroupVisible(R.id.schedule_filter_group, false)
        setDashboardMenuVisible(menu, true)
        menu.findItem(R.id.menu_notices)?.isVisible = !editModeController.editMode
        updateNoticesMenuIcon(menu)
        menu.findItem(R.id.menu_add_widget)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_open_profile)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_edit_layout)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_reset_layout)?.isVisible = !editModeController.editMode
        menu.findItem(R.id.menu_add_card)?.isVisible = editModeController.editMode
        menu.findItem(R.id.menu_done_editing)?.isVisible = editModeController.editMode
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (destination == MainDestination.SCHEDULING) {
            return super.onOptionsItemSelected(item)
        }
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
        outState.putString(STATE_DESTINATION, destination.name)
        super.onSaveInstanceState(outState)
    }

    // Orange dot on the drawer's settings cog while an app update is
    // available, mirroring official Samsung apps.
    private fun updateSettingsBadge() {
        findViewById<DrawerLayout>(R.id.main_toolbar_layout).setHeaderButtonBadge(
            if (TwidgetStore.updateAvailable(this)) Badge.DOT else Badge.NONE
        )
    }

    private fun checkReleasesOnLaunch() {
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
        val base = AppCompatResources.getDrawable(this, OneUiIconR.drawable.ic_oui_notice_outline) ?: return
        item.icon = if (ReleaseNoticesStore.hasUnseen(this)) {
            NoticeBadgeDrawable(base, getColor(R.color.notice_badge_orange), resources.displayMetrics.density)
        } else {
            base
        }
    }

    internal fun render() {
        accounts = TwidgetStore.accounts(this)
            .ifEmpty { listOf(TwidgetStore.settings(this).username) }
            .filter { it.isNotBlank() }
        if (accounts.isEmpty()) return
        if (selectedAccount.isBlank() || accounts.none { it.equals(selectedAccount, ignoreCase = true) }) {
            selectedAccount = accounts.first()
        }
        val dashboard = findViewById<SwipeRefreshLayout>(R.id.main_refresh)
        val schedule = findViewById<View>(R.id.main_schedule_host)
        if (destination == MainDestination.SCHEDULING) {
            dashboard.visibility = View.GONE
            dashboard.isEnabled = false
            schedule.visibility = View.VISIBLE
            showEmbeddedScheduleQueue()
        } else {
            hideEmbeddedScheduleQueue()
            schedule.visibility = View.GONE
            dashboard.visibility = View.VISIBLE
            dashboard.isEnabled = true
            dashboardBinder.bindContent()
        }
        if (::scheduleBackCallback.isInitialized) {
            scheduleBackCallback.isEnabled = destination == MainDestination.SCHEDULING
        }
        drawerController.buildDrawer()
        if (destination == MainDestination.SCHEDULING) {
            findViewById<ToolbarLayout>(R.id.main_toolbar_layout).setTitle(getString(R.string.schedule_title))
        } else {
            drawerController.renderHeader()
        }
        updateScheduleFabVisibility()
    }

    private fun setupScheduleAction() {
        findViewById<View>(R.id.schedule_fab).setOnClickListener {
            val defaultAccount = TwidgetStore.settings(this).username.trim().trimStart('@')
            startRightSidePopOverActivity(
                Intent(this, ScheduleComposeActivity::class.java)
                    .putExtra(ScheduleComposeActivity.EXTRA_USERNAME, defaultAccount)
            )
        }
    }

    internal fun updateScheduleFabVisibility() {
        val defaultAccount = TwidgetStore.settings(this).username
        findViewById<View>(R.id.schedule_fab)?.visibility = if (
            destination == MainDestination.DASHBOARD &&
                !editModeController.editMode && selectedAccount.equals(defaultAccount, ignoreCase = true)
        ) View.VISIBLE else View.GONE
    }

    private fun showScheduling() {
        editModeController.setEditMode(false)
        destination = MainDestination.SCHEDULING
        render()
        invalidateOptionsMenu()
    }

    private fun showDashboard() {
        destination = MainDestination.DASHBOARD
        render()
        invalidateOptionsMenu()
    }

    private fun openScheduleTrash() {
        startLeftSidePopOverActivity(
            Intent(this, ScheduleActivity::class.java)
                .putExtra(ScheduleActivity.EXTRA_OPEN_TRASH, true)
        )
    }

    private fun setDashboardMenuVisible(menu: Menu, visible: Boolean) {
        listOf(
            R.id.menu_notices,
            R.id.menu_add_widget,
            R.id.menu_open_profile,
            R.id.menu_edit_layout,
            R.id.menu_reset_layout,
            R.id.menu_add_card,
            R.id.menu_done_editing,
        ).forEach { menu.findItem(it)?.isVisible = visible }
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
            ComponentName(this, TwidgetWidget::class.java),
            null,
            null
        )
        Toast.makeText(this, R.string.add_widget_requested, Toast.LENGTH_SHORT).show()
    }

    internal fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class MainDestination {
        DASHBOARD,
        SCHEDULING,
    }

    private companion object {
        const val STATE_DESTINATION = "main_destination"
    }
}
