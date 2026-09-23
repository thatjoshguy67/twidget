package com.tjg.twidget.main

import com.tjg.twidget.BuildConfig

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.tjg.twidget.R
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.update.AppRelease
import com.tjg.twidget.update.AppUpdateManager
import com.tjg.twidget.update.AppVersion
import com.tjg.twidget.update.UpdateChannel
import com.tjg.twidget.update.UpdateNotificationHelper
import com.tjg.twidget.update.UpdateDownloadCancelledException
import com.tjg.twidget.update.UpdateDownloadController
import com.tjg.twidget.update.UpdateDownloadNotificationHelper
import dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout
import dev.oneuiproject.oneui.widget.CardItemView
import java.io.File
import kotlin.math.abs

class AboutActivity : FoldablePopOverActivity() {
    private var versionTapCount = 0
    private var headerIconTapCount = 0
    private var versionTapToast: Toast? = null
    private var updateCheckGeneration = 0
    private var availableRelease: AppRelease? = null
    private var pendingInstallApk: File? = null
    private var waitingForInstallPermission = false
    private var requestedInstallVersion: String? = null

    private val updateChannel: UpdateChannel
        get() = savedUpdateChannel(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedInstallVersion = intent.getStringExtra(EXTRA_INSTALL_VERSION)
            ?.takeIf { BuildConfig.IN_APP_UPDATES }
            ?.takeIf(String::isNotBlank)
        if (requestedInstallVersion != null) UpdateNotificationHelper.cancel(this)
        setContentView(R.layout.activity_about)
        applySystemBarInsets()
        setupToolbar()
        setupTransparentAppBar()
        setupVersion()
        setupHeaderIconBounce()
        setupAdaptiveLayout()
        setupResponsiveHeroHeight()
        setupCollapsingContent()
        setupCreditAvatars()
        setupRefresh()
        setupUpdates()

        findViewById<View>(R.id.about_tjg_credit).setOnClickListener {
            openUrl(getString(R.string.link_tjg))
        }
        findViewById<View>(R.id.about_kingowen_credit).setOnClickListener {
            openUrl(getString(R.string.link_kingowen))
        }
        findViewById<View>(R.id.about_aaron_credit).setOnClickListener {
            openUrl(getString(R.string.link_aaron))
        }
        findViewById<View>(R.id.about_fxtwitter_credit).setOnClickListener {
            openUrl(getString(R.string.link_fxtwitter))
        }
        findViewById<View>(R.id.about_oneui_credit).setOnClickListener {
            openUrl(getString(R.string.link_oneui_project))
        }
        findViewById<View>(R.id.about_privacy_policy).setOnClickListener {
            openUrl(getString(R.string.link_privacy_policy))
        }
        findViewById<View>(R.id.about_open_source_licenses).setOnClickListener {
            showOpenSourceLicenses()
        }
        findViewById<View>(R.id.about_legal_disclaimer).setOnClickListener {
            showLegalNotice()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_APP_INFO, 1, R.string.app_info)
            .setIcon(R.drawable.ic_settings_info)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        if (!BuildConfig.IN_APP_UPDATES) return true
        val channels = menu.addSubMenu(Menu.NONE, MENU_UPDATE_CHANNEL, 2, R.string.settings_update_channel)
        channels.item.setIcon(R.drawable.ic_settings_labs)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        channels.add(MENU_UPDATE_CHANNEL, MENU_STABLE, 0, R.string.update_channel_stable)
            .setCheckable(true)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        channels.add(MENU_UPDATE_CHANNEL, MENU_BETA, 2, R.string.update_channel_beta)
            .setCheckable(true)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        if (AppUpdateManager.isDebugBuild(appVersionName())) {
            channels.add(MENU_UPDATE_CHANNEL, MENU_DEBUG, 2, R.string.update_channel_debug)
                .setCheckable(true)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        channels.setGroupCheckable(MENU_UPDATE_CHANNEL, true, true)
        val selectedItem = when (updateChannel) {
            UpdateChannel.STABLE -> MENU_STABLE
            UpdateChannel.BETA -> MENU_BETA
            UpdateChannel.DEBUG -> MENU_DEBUG
        }
        menu.findItem(selectedItem)?.isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (item.itemId == MENU_APP_INFO) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
            return true
        }
        if (BuildConfig.IN_APP_UPDATES && (item.itemId == MENU_STABLE || item.itemId == MENU_BETA ||
            (item.itemId == MENU_DEBUG && AppUpdateManager.isDebugBuild(appVersionName())))
        ) {
            val channel = when (item.itemId) {
                MENU_BETA -> UpdateChannel.BETA
                MENU_DEBUG -> UpdateChannel.DEBUG
                else -> UpdateChannel.STABLE
            }
            getPreferences(MODE_PRIVATE).edit()
                .putString(PREF_UPDATE_CHANNEL, channel.name)
                .remove(PREF_BETA_RELEASES)
                .apply()
            item.isChecked = true
            checkForUpdates(channel)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.IN_APP_UPDATES && waitingForInstallPermission && packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = false
            pendingInstallApk?.let(::launchPackageInstaller)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedInstallVersion = intent.getStringExtra(EXTRA_INSTALL_VERSION)
            ?.takeIf { BuildConfig.IN_APP_UPDATES }
            ?.takeIf(String::isNotBlank)
        if (requestedInstallVersion == null) return
        UpdateNotificationHelper.cancel(this)
        val release = availableRelease
        if (release != null && release.version.toString() == requestedInstallVersion) {
            requestedInstallVersion = null
            downloadUpdate(release)
        } else {
            checkForUpdates(updateChannel)
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.about_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
    }

    private fun setupTransparentAppBar() {
        findViewById<AppBarLayout>(R.id.about_app_bar).apply {
            setBackgroundColor(Color.TRANSPARENT)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            elevation = 0f
            stateListAnimator = null
        }
        findViewById<CollapsingToolbarLayout>(R.id.about_collapsing_toolbar).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setContentScrim(ColorDrawable(Color.TRANSPARENT))
            statusBarScrim = ColorDrawable(Color.TRANSPARENT)
        }
        findViewById<Toolbar>(R.id.about_toolbar).apply {
            setBackgroundColor(Color.TRANSPARENT)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            elevation = 0f
        }
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.about_root)
        val appBar = findViewById<AppBarLayout>(R.id.about_app_bar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Keep the root (and its scroll fade) edge-to-edge so the transparent
            // status bar always reflects the current background. Only the app bar
            // content needs to start below the status icons.
            view.setPadding(bars.left, 0, bars.right, 0)
            appBar.setPadding(0, bars.top, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupVersion() {
        val text = getString(R.string.about_version, appVersionName())
        val feedback = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, feedback, true)
        val versionFeedback = feedback.resourceId
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, feedback, true)
        val iconFeedback = feedback.resourceId
        findViewById<TextView>(R.id.about_header_version).apply {
            this.text = text
            setBackgroundResource(versionFeedback)
            isFocusable = true
            setOnClickListener { onVersionTapped() }
        }
        findViewById<CardItemView>(R.id.about_compact_header).apply {
            summary = text
            getSummaryView().apply {
                setBackgroundResource(versionFeedback)
                isFocusable = true
                setOnClickListener { onVersionTapped() }
            }
            getEndImageView().apply {
                contentDescription = getString(R.string.about_repo_link)
                val touchSize = (48 * resources.displayMetrics.density).toInt()
                layoutParams = layoutParams.apply {
                    width = touchSize
                    height = touchSize
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setBackgroundResource(iconFeedback)
                isFocusable = true
                setOnClickListener { openUrl(getString(R.string.link_app_repo)) }
            }
        }
    }

    private fun setupHeaderIconBounce() {
        findViewById<View>(R.id.about_header_icon).apply {
            setOnTouchListener { icon, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        icon.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        icon.animate().cancel()
                        icon.animate()
                            .scaleX(0.86f)
                            .scaleY(0.86f)
                            .setDuration(70L)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        icon.animate().cancel()
                        icon.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(320L)
                            .setInterpolator(OvershootInterpolator(2.5f))
                            .start()
                    }
                }
                false
            }
            setOnClickListener {
                headerIconTapCount += 1
                if (headerIconTapCount == HEADER_ICON_EASTER_EGG_TAPS) {
                    headerIconTapCount = 0
                    openUrl(HEADER_ICON_EASTER_EGG_URL)
                }
            }
        }
    }

    private fun setupAdaptiveLayout() {
        findViewById<AdaptiveCoordinatorLayout>(R.id.about_root).configureAdaptiveMargin(
            AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ADP_DEFAULT,
            setOf(
                findViewById(R.id.about_app_bar),
                findViewById(R.id.about_refresh),
            ),
        )
    }

    private fun setupRefresh() {
        if (!BuildConfig.IN_APP_UPDATES) {
            findViewById<SwipeRefreshLayout>(R.id.about_refresh).isEnabled = false
            return
        }
        val appBar = findViewById<AppBarLayout>(R.id.about_app_bar)
        findViewById<SwipeRefreshLayout>(R.id.about_refresh).apply {
            setOnChildScrollUpCallback { _, child ->
                appBar.y < 0f || child?.canScrollVertically(-1) == true
            }
            setOnRefreshListener { checkForUpdates(updateChannel) }
        }
    }

    private fun setupResponsiveHeroHeight() {
        val root = findViewById<View>(R.id.about_root)
        val appBar = findViewById<AppBarLayout>(R.id.about_app_bar)
        val header = findViewById<View>(R.id.about_header_content)
        val baseProportion = if (
            resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP
        ) {
            LARGE_SCREEN_HERO_HEIGHT_PROPORTION
        } else {
            DEFAULT_HERO_HEIGHT_PROPORTION
        }
        var appliedProportion = -1f

        val updateHeight: () -> Unit = {
            if (root.height > 0 && header.measuredHeight > 0) {
                val topMargin = (header.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
                val breathingRoom =
                    (HERO_BREATHING_ROOM_DP * resources.displayMetrics.density).toInt()
                // The initial half-height app bar can constrain the hero child
                // before this callback runs. Use the header's designed height as
                // the floor so that truncated first measurement does not become
                // the final hero height on short or high-density displays.
                val headerHeight = maxOf(
                    header.measuredHeight,
                    resources.getDimensionPixelSize(R.dimen.about_hero_content_height),
                )
                // SESL AppBarLayout injects its own extended bottom padding even when the
                // layout XML declares none. Include it or the scrolling sibling starts over
                // the final part of the header on shorter and foldable displays.
                val requiredHeight = appBar.paddingTop +
                    appBar.paddingBottom +
                    topMargin +
                    headerHeight +
                    breathingRoom
                val requiredProportion = requiredHeight.toFloat() / root.height
                val nextProportion = requiredProportion.coerceIn(
                    baseProportion,
                    MAX_HERO_HEIGHT_PROPORTION,
                )
                if (abs(nextProportion - appliedProportion) > 0.001f) {
                    appliedProportion = nextProportion
                    appBar.seslSetCustomHeightProportion(true, nextProportion)
                }
            }
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHeight() }
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateHeight() }
        root.post { updateHeight() }
    }

    // Seven taps on the version number unlock the hidden debug menu in
    // Settings, Android developer-options style.
    private fun onVersionTapped() {
        if (TwidgetStore.debugMenuUnlocked(this)) {
            showVersionTapToast(getString(R.string.debug_menu_already_unlocked))
            return
        }
        versionTapCount++
        val remaining = DEBUG_UNLOCK_TAPS - versionTapCount
        when {
            remaining <= 0 -> {
                TwidgetStore.setDebugMenuUnlocked(this, true)
                showVersionTapToast(getString(R.string.debug_menu_unlocked))
            }
            remaining <= 3 -> showVersionTapToast(getString(R.string.debug_menu_countdown, remaining))
        }
    }

    private fun showVersionTapToast(message: String) {
        versionTapToast?.cancel()
        versionTapToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun appVersionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    }

    private fun setupUpdates() {
        if (!BuildConfig.IN_APP_UPDATES) {
            hideUpdateUi()
            return
        }
        findViewById<AppCompatButton>(R.id.about_update_button).setOnClickListener {
            availableRelease?.let(::downloadUpdate)
        }
        checkForUpdates(updateChannel)
    }

    private fun checkForUpdates(channel: UpdateChannel) {
        if (!BuildConfig.IN_APP_UPDATES) return
        val generation = ++updateCheckGeneration
        availableRelease = null
        if (TwidgetStore.fakeUpdateAvailable(this)) {
            val release = fakeRelease()
            availableRelease = release
            showUpdateAvailable(release)
            maybeInstallRequestedUpdate(release)
            finishPullRefresh()
            return
        }
        showUpdateChecking()
        AppExecutors.execute(
            onRejected = {
                runOnUiThread {
                    if (generation != updateCheckGeneration) return@runOnUiThread
                    hideUpdateUi()
                    finishPullRefresh()
                }
            },
        ) {
            val result = runCatching {
                AppUpdateManager.findUpdate(TwidgetStore.updateCheckVersion(this, appVersionName()), channel)
            }
            runOnUiThread {
                if (generation != updateCheckGeneration || isFinishing || isDestroyed) return@runOnUiThread
                // Only a completed check may flip the persisted badge state;
                // a network failure keeps whatever the last check concluded.
                result.onSuccess { release ->
                    TwidgetStore.setUpdateAvailable(this, release != null, release?.version?.toString())
                }
                val release = result.getOrNull()
                availableRelease = release
                if (release == null) {
                    hideUpdateUi()
                } else {
                    showUpdateAvailable(release)
                    maybeInstallRequestedUpdate(release)
                }
                finishPullRefresh()
            }
        }
    }

    private fun finishPullRefresh() {
        findViewById<SwipeRefreshLayout>(R.id.about_refresh).isRefreshing = false
    }

    // Debug aid: pretend the next minor version has been published so the
    // update button and badges can be exercised without a real release.
    private fun fakeRelease(): AppRelease {
        val current = AppVersion.parse(appVersionName()) ?: AppVersion(1, 0, 0, null, null)
        val next = AppVersion(current.major, current.minor + 1, 0, null, null)
        return AppRelease(
            version = next,
            assetName = "twidget-v$next.apk",
            downloadUrl = "https://github.com/thatjoshguy67/twidget/releases/download/twidget-v$next/twidget-v$next.apk",
            prerelease = false,
        )
    }

    private fun showUpdateChecking() {
        findViewById<View>(R.id.about_update_action).visibility = View.VISIBLE
        findViewById<AppCompatButton>(R.id.about_update_button).visibility = View.GONE
        findViewById<View>(R.id.about_update_spinner).visibility = View.VISIBLE
    }

    private fun showUpdateAvailable(release: AppRelease) {
        hideUpdateSpinner()
        findViewById<View>(R.id.about_update_action).visibility = View.VISIBLE
        findViewById<AppCompatButton>(R.id.about_update_button).apply {
            contentDescription = getString(R.string.update_to_version, release.version.toString())
            isEnabled = true
            visibility = View.VISIBLE
        }
    }

    private fun hideUpdateUi() {
        hideUpdateSpinner()
        findViewById<AppCompatButton>(R.id.about_update_button).visibility = View.GONE
        // Keep the reserved action slot in the layout so the icon, title and
        // version never move when an update check changes state.
        findViewById<View>(R.id.about_update_action).visibility = View.INVISIBLE
    }

    private fun hideUpdateSpinner() {
        findViewById<View>(R.id.about_update_spinner).visibility = View.GONE
    }

    private fun downloadUpdate(release: AppRelease) {
        if (!BuildConfig.IN_APP_UPDATES) return
        if (!UpdateDownloadController.tryBegin()) {
            Toast.makeText(this, R.string.update_download_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        val generation = ++updateCheckGeneration
        findViewById<AppCompatButton>(R.id.about_update_button).apply {
            isEnabled = false
            visibility = View.GONE
        }
        showUpdateChecking()
        UpdateDownloadNotificationHelper.show(this, com.tjg.twidget.update.UpdateDownloadProgress(0L, -1L))
        val startMessage = if (UpdateDownloadNotificationHelper.notificationsAvailable(this)) {
            R.string.update_download_started
        } else {
            R.string.update_download_started_without_notification
        }
        Toast.makeText(this, startMessage, Toast.LENGTH_LONG).show()
        AppExecutors.execute(
            onRejected = { runOnUiThread {
                UpdateDownloadController.finish()
                UpdateDownloadNotificationHelper.cancel(applicationContext)
                if (!isFinishing && !isDestroyed) showDownloadFailure(release)
            } },
        ) {
            val result = runCatching {
                AppUpdateManager.download(
                    release,
                    File(cacheDir, "updates"),
                    onProgress = { UpdateDownloadNotificationHelper.show(applicationContext, it) },
                    awaitPermissionToContinue = UpdateDownloadController::awaitPermissionToContinue,
                )
            }
            val apk = result.getOrNull()
            runOnUiThread {
                UpdateDownloadController.finish()
                if (isFinishing || isDestroyed) {
                    UpdateDownloadNotificationHelper.cancel(applicationContext)
                    apk?.delete()
                    return@runOnUiThread
                }
                if (generation != updateCheckGeneration) {
                    UpdateDownloadNotificationHelper.cancel(applicationContext)
                    apk?.delete()
                    return@runOnUiThread
                }
                if (apk == null) {
                    UpdateDownloadNotificationHelper.cancel(this)
                    if (result.exceptionOrNull() is UpdateDownloadCancelledException) showUpdateAvailable(release)
                    else showDownloadFailure(release)
                } else if (!isValidUpdateApk(apk, release)) {
                    UpdateDownloadNotificationHelper.cancel(this)
                    apk.delete()
                    showUpdateAvailable(release)
                    Toast.makeText(this, R.string.update_invalid_apk, Toast.LENGTH_LONG).show()
                } else {
                    UpdateDownloadNotificationHelper.showCompleted(this)
                    pendingInstallApk = apk
                    window.decorView.postDelayed({
                        if (!isFinishing && !isDestroyed) beginInstall(apk)
                    }, 350L)
                }
            }
        }
    }

    private fun showDownloadFailure(release: AppRelease) {
        showUpdateAvailable(release)
        Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show()
    }

    private fun maybeInstallRequestedUpdate(release: AppRelease) {
        if (requestedInstallVersion != release.version.toString()) return
        requestedInstallVersion = null
        UpdateNotificationHelper.cancel(this)
        downloadUpdate(release)
    }

    private fun isValidUpdateApk(apk: File, release: AppRelease): Boolean {
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        val archiveVersion = archive.versionName?.let(AppVersion::parse) ?: return false
        return archive.packageName == packageName && archiveVersion == release.version
    }

    private fun beginInstall(apk: File) {
        if (!BuildConfig.IN_APP_UPDATES) return
        hideUpdateUi()
        if (packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(apk)
            return
        }
        waitingForInstallPermission = true
        Toast.makeText(this, R.string.update_install_permission, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun launchPackageInstaller(apk: File) {
        if (!BuildConfig.IN_APP_UPDATES) return
        val uri = FileProvider.getUriForFile(this, "$packageName.update_files", apk)
        startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            },
        )
    }

    private fun setupCollapsingContent() {
        val content = findViewById<View>(R.id.about_content)
        val header = findViewById<View>(R.id.about_header_content)
        val hint = findViewById<View>(R.id.about_swipe_hint)
        val gradientFade = findViewById<View>(R.id.about_gradient_fade)
        content.alpha = 0f
        findViewById<AppBarLayout>(R.id.about_app_bar).addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
                val range = appBar.totalScrollRange.coerceAtLeast(1)
                val progress = abs(verticalOffset).toFloat() / range
                content.alpha = ((progress - 0.25f) / 0.55f).coerceIn(0f, 1f)
                // Clear the fixed hero before it passes behind the pinned,
                // transparent toolbar during collapse.
                header.alpha = (1f - progress * 2f).coerceIn(0f, 1f)
                // The gradient belongs to the expanded hero; scrolling settles
                // the page onto the plain One UI background.
                // Let the hero recede early in the scroll, leaving the settled
                // One UI background in place for the remainder of the page.
                gradientFade.alpha = (progress * 2f).coerceIn(0f, 1f)
                hint.alpha = (1f - progress * 2f).coerceIn(0f, 1f)
                hint.visibility = if (hint.alpha == 0f) View.INVISIBLE else View.VISIBLE
            }
        )
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun setupCreditAvatars() {
        loadCreditAvatar(R.id.about_tjg_credit, TJG_X_USERNAME)
        loadCreditAvatar(R.id.about_kingowen_credit, KINGOWEN_X_USERNAME)
        loadCreditAvatar(R.id.about_aaron_credit, AARON_X_USERNAME)
    }

    private fun loadCreditAvatar(rowId: Int, username: String) {
        val row = findViewById<CardItemView>(rowId).apply {
            iconSize = (34 * resources.displayMetrics.density).toInt()
            icon = getDrawable(R.drawable.avatar_twidget)
        }
        ProfileImageLoader.loadInto(
            this,
            row.getIconImageView(),
            "https://unavatar.io/twitter/$username",
        )
    }

    private fun showOpenSourceLicenses() {
        val notices = resources.openRawResource(R.raw.open_source_licenses)
            .bufferedReader()
            .use { it.readText() }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_open_source_licenses_title)
            .setMessage(notices)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun showLegalNotice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_legal_notice_title)
            .setMessage(R.string.about_legal_disclaimer_summary)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val EXTRA_INSTALL_VERSION = "install_update_version"

        fun installUpdateIntent(context: Context, version: String): Intent =
            Intent(context, AboutActivity::class.java)
                .putExtra(EXTRA_INSTALL_VERSION, version)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        internal fun savedUpdateChannel(context: Context): UpdateChannel {
            val preferences = context.getSharedPreferences("AboutActivity", MODE_PRIVATE)
            val installedVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
            val defaultChannel = AppUpdateManager.defaultUpdateChannel(installedVersion)
            preferences.getString(PREF_UPDATE_CHANNEL, null)
                ?.let { saved -> runCatching { UpdateChannel.valueOf(saved) }.getOrNull() }
                ?.let { saved ->
                    return if (saved == UpdateChannel.DEBUG &&
                        !AppUpdateManager.isDebugBuild(installedVersion)
                    ) {
                        defaultChannel
                    } else {
                        saved
                    }
                }
            if (preferences.contains(PREF_BETA_RELEASES)) {
                return if (preferences.getBoolean(PREF_BETA_RELEASES, false)) {
                    UpdateChannel.BETA
                } else {
                    UpdateChannel.STABLE
                }
            }
            return defaultChannel
        }

        private const val LARGE_SCREEN_MIN_WIDTH_DP = 600
        private const val DEFAULT_HERO_HEIGHT_PROPORTION = 0.5f
        private const val LARGE_SCREEN_HERO_HEIGHT_PROPORTION = 0.58f
        private const val MAX_HERO_HEIGHT_PROPORTION = 0.9f
        private const val HERO_BREATHING_ROOM_DP = 24

        private const val DEBUG_UNLOCK_TAPS = 7
        private const val HEADER_ICON_EASTER_EGG_TAPS = 7
        private const val HEADER_ICON_EASTER_EGG_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        private const val TJG_X_USERNAME = "thatjoshguy69"
        private const val KINGOWEN_X_USERNAME = "KingOwenFYI"
        private const val AARON_X_USERNAME = "aaronthetechie"
        private const val PREF_BETA_RELEASES = "beta_releases"
        private const val PREF_UPDATE_CHANNEL = "update_channel"
        private const val MENU_APP_INFO = 1
        private const val MENU_UPDATE_CHANNEL = 2
        private const val MENU_STABLE = 3
        private const val MENU_BETA = 4
        private const val MENU_DEBUG = 6
    }
}
