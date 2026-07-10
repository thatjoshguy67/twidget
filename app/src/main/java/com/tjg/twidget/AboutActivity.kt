package com.tjg.twidget

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import kotlin.math.abs

class AboutActivity : FoldablePopOverActivity() {
    private var versionTapCount = 0
    private var versionTapToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        makeSystemBarsTransparent()
        setContentView(R.layout.activity_about)
        applySystemBarInsets()
        setupToolbar()
        setupTransparentAppBar()
        setupVersion()
        setupCollapsingContent()

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
        findViewById<View>(R.id.about_header_github).setOnClickListener {
            openUrl(getString(R.string.link_app_repo))
        }
        findViewById<View>(R.id.about_open_source_licenses).setOnClickListener {
            showOpenSourceLicenses()
        }
        findViewById<View>(R.id.about_legal_disclaimer).setOnClickListener {
            showLegalNotice()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(R.string.app_info)
            .setIcon(R.drawable.ic_info_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        if (item.title == getString(R.string.app_info)) {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
            return true
        }
        return super.onOptionsItemSelected(item)
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

    private fun makeSystemBarsTransparent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
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
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
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
        private const val DEBUG_UNLOCK_TAPS = 7
    }
}
