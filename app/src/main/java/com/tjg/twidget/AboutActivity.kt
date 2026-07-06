package com.tjg.twidget

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.AppBarLayout
import kotlin.math.abs

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        applySystemBarInsets()
        setupToolbar()
        setupVersion()
        setupCollapsingContent()

        findViewById<View>(R.id.about_tjg_credit).setOnClickListener {
            openUrl(getString(R.string.link_tjg))
        }
        findViewById<View>(R.id.about_oneui_credit).setOnClickListener {
            openUrl(getString(R.string.link_oneui_project))
        }
        findViewById<View>(R.id.about_tjg).setOnClickListener {
            openUrl(getString(R.string.link_tjg))
        }
        findViewById<View>(R.id.about_oneui).setOnClickListener {
            openUrl(getString(R.string.link_oneui_project))
        }
        findViewById<View>(R.id.about_repo).setOnClickListener {
            openUrl(getString(R.string.link_app_repo))
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

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.about_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupVersion() {
        val text = getString(R.string.about_version, appVersionName())
        findViewById<TextView>(R.id.about_header_version).text = text
        findViewById<TextView>(R.id.about_compact_version).text = text
    }

    private fun appVersionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    }

    private fun setupCollapsingContent() {
        val content = findViewById<View>(R.id.about_content)
        val hint = findViewById<View>(R.id.about_swipe_hint)
        content.alpha = 0f
        findViewById<AppBarLayout>(R.id.about_app_bar).addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
                val range = appBar.totalScrollRange.coerceAtLeast(1)
                val progress = abs(verticalOffset).toFloat() / range
                content.alpha = ((progress - 0.25f) / 0.55f).coerceIn(0f, 1f)
                hint.alpha = (1f - progress * 2f).coerceIn(0f, 1f)
                hint.visibility = if (hint.alpha == 0f) View.INVISIBLE else View.VISIBLE
            }
        )
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
