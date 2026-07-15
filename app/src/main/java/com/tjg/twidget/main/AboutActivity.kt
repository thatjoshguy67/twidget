package com.tjg.twidget.main

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ScrollView
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
import com.tjg.twidget.ui.OneUiSpinner
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.update.AppRelease
import com.tjg.twidget.update.AppUpdateManager
import com.tjg.twidget.update.AppVersion
import com.tjg.twidget.update.UpdateChannel
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

    private val updateChannel: UpdateChannel
        get() = savedUpdateChannel(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        findViewById<View>(R.id.about_fxtwitter_credit).setOnClickListener {
            openUrl(getString(R.string.link_fxtwitter))
        }
        findViewById<View>(R.id.about_oneui_credit).setOnClickListener {
            openUrl(getString(R.string.link_oneui_project))
        }
        findViewById<View>(R.id.about_open_source_licenses).setOnClickListener {
            showOpenSourceLicenses()
        }
        findViewById<View>(R.id.about_legal_disclaimer).setOnClickListener {
            showLegalNotice()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_GITHUB, 0, R.string.about_repo_link)
            .setIcon(R.drawable.ic_github_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(Menu.NONE, MENU_APP_INFO, 1, R.string.app_info)
            .setIcon(R.drawable.ic_info_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(MENU_UPDATE_CHANNEL, MENU_STABLE, 2, R.string.update_channel_stable)
            .setCheckable(true)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(MENU_UPDATE_CHANNEL, MENU_BETA, 2, R.string.update_channel_beta)
            .setCheckable(true)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        if (AppUpdateManager.isDebugBuild(appVersionName())) {
            menu.add(MENU_UPDATE_CHANNEL, MENU_DEBUG, 2, R.string.update_channel_debug)
                .setCheckable(true)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.setGroupCheckable(MENU_UPDATE_CHANNEL, true, true)
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
        if (item.itemId == MENU_GITHUB) {
            openUrl(getString(R.string.link_app_repo))
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
        if (item.itemId == MENU_STABLE || item.itemId == MENU_BETA ||
            (item.itemId == MENU_DEBUG && AppUpdateManager.isDebugBuild(appVersionName()))
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
        if (waitingForInstallPermission && packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = false
            pendingInstallApk?.let(::launchPackageInstaller)
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
        listOf(R.id.about_header_version, R.id.about_compact_version).forEach { id ->
            findViewById<TextView>(id).apply {
                this.text = text
                setOnClickListener { onVersionTapped() }
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
        val appBar = findViewById<AppBarLayout>(R.id.about_app_bar)
        findViewById<SwipeRefreshLayout>(R.id.about_refresh).apply {
            setOnChildScrollUpCallback { _, child ->
                appBar.y < 0f || child?.canScrollVertically(-1) == true
            }
            setOnRefreshListener { checkForUpdates(updateChannel) }
        }
    }

    private fun setupResponsiveHeroHeight() {
        if (resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_MIN_WIDTH_DP) {
            findViewById<AppBarLayout>(R.id.about_app_bar)
                .seslSetCustomHeightProportion(true, LARGE_SCREEN_HERO_HEIGHT_PROPORTION)
        }
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
        findViewById<AppCompatButton>(R.id.about_update_button).setOnClickListener {
            availableRelease?.let(::downloadUpdate)
        }
        checkForUpdates(updateChannel)
    }

    private fun checkForUpdates(channel: UpdateChannel) {
        val generation = ++updateCheckGeneration
        availableRelease = null
        if (TwidgetStore.fakeUpdateAvailable(this)) {
            val release = fakeRelease()
            availableRelease = release
            showUpdateAvailable(release)
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
                AppUpdateManager.findUpdate(appVersionName(), channel)
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
                if (release == null) hideUpdateUi() else showUpdateAvailable(release)
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
        findViewById<AppCompatButton>(R.id.about_update_button).visibility = View.GONE
        findViewById<ImageView>(R.id.about_update_spinner).apply {
            visibility = View.VISIBLE
            setImageResource(R.drawable.oneui_spinner)
            OneUiSpinner.loop(this)
        }
    }

    private fun showUpdateAvailable(release: AppRelease) {
        hideUpdateSpinner()
        findViewById<AppCompatButton>(R.id.about_update_button).apply {
            contentDescription = getString(R.string.update_to_version, release.version.toString())
            isEnabled = true
            visibility = View.VISIBLE
        }
    }

    private fun hideUpdateUi() {
        hideUpdateSpinner()
        findViewById<AppCompatButton>(R.id.about_update_button).visibility = View.GONE
    }

    private fun hideUpdateSpinner() {
        findViewById<ImageView>(R.id.about_update_spinner).apply {
            (drawable as? Animatable)?.stop()
            setImageDrawable(null)
            visibility = View.GONE
        }
    }

    private fun downloadUpdate(release: AppRelease) {
        val generation = ++updateCheckGeneration
        findViewById<AppCompatButton>(R.id.about_update_button).apply {
            isEnabled = false
            visibility = View.GONE
        }
        showUpdateChecking()
        AppExecutors.execute(
            onRejected = { runOnUiThread { showDownloadFailure(release) } },
        ) {
            val apk = runCatching {
                AppUpdateManager.download(release, File(cacheDir, "updates"))
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (generation != updateCheckGeneration) {
                    apk?.delete()
                    return@runOnUiThread
                }
                if (apk == null) {
                    showDownloadFailure(release)
                } else if (!isValidUpdateApk(apk, release)) {
                    apk.delete()
                    showUpdateAvailable(release)
                    Toast.makeText(this, R.string.update_invalid_apk, Toast.LENGTH_LONG).show()
                } else {
                    pendingInstallApk = apk
                    beginInstall(apk)
                }
            }
        }
    }

    private fun showDownloadFailure(release: AppRelease) {
        showUpdateAvailable(release)
        Toast.makeText(this, R.string.update_download_failed, Toast.LENGTH_LONG).show()
    }

    private fun isValidUpdateApk(apk: File, release: AppRelease): Boolean {
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        val archiveVersion = archive.versionName?.let(AppVersion::parse) ?: return false
        return archive.packageName == packageName && archiveVersion == release.version
    }

    private fun beginInstall(apk: File) {
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
        val hint = findViewById<View>(R.id.about_swipe_hint)
        val gradientFade = findViewById<View>(R.id.about_gradient_fade)
        content.alpha = 0f
        findViewById<AppBarLayout>(R.id.about_app_bar).addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
                val range = appBar.totalScrollRange.coerceAtLeast(1)
                val progress = abs(verticalOffset).toFloat() / range
                content.alpha = ((progress - 0.25f) / 0.55f).coerceIn(0f, 1f)
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
    }

    private fun loadCreditAvatar(rowId: Int, username: String) {
        val row = findViewById<CardItemView>(rowId).apply {
            iconSize = (48 * resources.displayMetrics.density).toInt()
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
        val padding = (24 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            text = notices
            setTextColor(getColor(R.color.oneui_text_primary))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(padding, padding / 2, padding, padding)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_open_source_licenses_title)
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showLegalNotice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_legal_notice_title)
            .setMessage(R.string.about_legal_disclaimer_summary)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
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
        private const val LARGE_SCREEN_HERO_HEIGHT_PROPORTION = 0.58f

        private const val DEBUG_UNLOCK_TAPS = 7
        private const val HEADER_ICON_EASTER_EGG_TAPS = 7
        private const val HEADER_ICON_EASTER_EGG_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        private const val TJG_X_USERNAME = "thatjoshguy69"
        private const val KINGOWEN_X_USERNAME = "KingOwenFYI"
        private const val PREF_BETA_RELEASES = "beta_releases"
        private const val PREF_UPDATE_CHANNEL = "update_channel"
        private const val MENU_APP_INFO = 1
        private const val MENU_UPDATE_CHANNEL = 2
        private const val MENU_STABLE = 3
        private const val MENU_BETA = 4
        private const val MENU_GITHUB = 5
        private const val MENU_DEBUG = 6
    }
}
