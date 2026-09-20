package com.tjg.twidget.analytics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatButton
import com.tjg.twidget.R
import com.tjg.twidget.bridge.BridgeImportException
import com.tjg.twidget.bridge.HistoryPool
import com.tjg.twidget.core.AppExecutors
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.EdgeToEdgeActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.widget.TwidgetWidget
import dev.oneuiproject.oneui.widget.AdaptiveCoordinatorLayout
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

class AnalyticsImportActivity : EdgeToEdgeActivity() {
    private lateinit var username: String
    private lateinit var stats: ProfileStats
    private var parsedImport: XAnalyticsImport? = null
    private var step = STEP_INTRO
    private var asyncGeneration = 0L

    private val csvPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readCsv(uri)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when (step) {
                STEP_CONFIRM -> renderStep(STEP_INTRO)
                else -> cancelAndFinish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
        if (username.isBlank() || TwidgetStore.accounts(this).none { it.equals(username, true) }) {
            finish()
            return
        }
        stats = TwidgetStore.currentStats(this, username)
        setContentView(R.layout.activity_import_analytics)
        findViewById<AdaptiveCoordinatorLayout>(R.id.import_root).configureAdaptiveMargin(
            AdaptiveCoordinatorLayout.MARGIN_PROVIDER_ADP_DEFAULT,
            setOf(findViewById<View>(R.id.import_content)),
        )
        onBackPressedDispatcher.addCallback(this, backCallback)
        setupAnalyticsLink()
        setupButtons()
        renderStep(STEP_INTRO, animate = false)
    }

    override fun onDestroy() {
        asyncGeneration++
        super.onDestroy()
    }

    private fun setupAnalyticsLink() {
        val summary = getString(R.string.import_get_stats_summary)
        val link = "analytics.x.com"
        val start = summary.indexOf(link)
        findViewById<TextView>(R.id.import_analytics_summary).apply {
            if (start >= 0) {
                text = SpannableString(summary).apply {
                    setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) = openAnalyticsInBrowser()
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = getColor(R.color.oneui_accent)
                            ds.isUnderlineText = false
                        }
                    }, start, start + link.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                movementMethod = LinkMovementMethod.getInstance()
                highlightColor = getColor(android.R.color.transparent)
            }
        }
        findViewById<View>(R.id.import_analytics_row).setOnClickListener { openAnalyticsInBrowser() }
    }

    private fun openAnalyticsInBrowser() {
        val browserPackages = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            0,
        ).map { it.activityInfo.packageName }.distinct()
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.analytics_url))).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val defaultPackage = packageManager.resolveActivity(webIntent, 0)?.activityInfo?.packageName
        val browserPackage = defaultPackage?.takeIf(browserPackages::contains) ?: browserPackages.firstOrNull()

        if (browserPackage == null) {
            Toast.makeText(this, R.string.import_no_browser, Toast.LENGTH_SHORT).show()
            return
        }

        val browserIntent = webIntent.setPackage(browserPackage)
        runCatching { startActivity(browserIntent) }
            .onFailure { Toast.makeText(this, R.string.import_no_browser, Toast.LENGTH_SHORT).show() }
    }

    private fun setupButtons() {
        findViewById<AppCompatButton>(R.id.import_secondary_button).setOnClickListener {
            cancelAndFinish()
        }
        findViewById<AppCompatButton>(R.id.import_primary_button).setOnClickListener {
            when (step) {
                STEP_INTRO, STEP_FAILURE -> csvPicker.launch(arrayOf("text/csv", "text/*", "application/csv", "application/vnd.ms-excel"))
                STEP_CONFIRM -> startImport()
                STEP_SUCCESS -> finish()
            }
        }
    }

    private fun readCsv(uri: Uri) {
        parsedImport = null
        stats = TwidgetStore.currentStats(this, username)
        val result = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                XAnalyticsCsvImporter.parse(reader, stats.followersCount)
            } ?: throw IllegalArgumentException(getString(R.string.analytics_import_cannot_open))
        }
        result.onSuccess { parsed ->
            parsedImport = parsed
            bindConfirmation(parsed)
            renderStep(STEP_CONFIRM)
        }.onFailure { error ->
            showFailure(error)
        }
    }

    private fun bindConfirmation(parsed: XAnalyticsImport) {
        findViewById<TextView>(R.id.import_account_title).text = getString(R.string.import_for_account, username)
        findViewById<TextView>(R.id.import_range_value).text = formatRange(parsed.firstDate, parsed.lastDate)
        findViewById<TextView>(R.id.import_detected_value).text = parsed.detectedFollowers
            ?.let(::formatCount)
            ?: getString(R.string.import_followers_not_detected)
        val metrics = parsed.movements
            .flatMap { it.analyticsValues() }
            .filter { it.second != null }
            .map { it.first }
            .distinct()
        findViewById<TextView>(R.id.import_metrics_value).text =
            getString(R.string.import_metrics_detected_count, metrics.size)
        ProfileImageLoader.loadInto(this, findViewById(R.id.import_account_avatar), stats.profileImage)
    }

    private fun startImport() {
        val parsed = parsedImport ?: return
        renderStep(STEP_LOADING)
        val generation = ++asyncGeneration
        val settings = TwidgetStore.settings(this)
        val useBridge = settings.shareHistory ||
            settings.dataSource == TwidgetStore.DATA_SOURCE_DEFAULT ||
            settings.dataSource == TwidgetStore.DATA_SOURCE_SELF_HOSTED
        AppExecutors.execute(onRejected = {
            if (generation == asyncGeneration && !isFinishing) showFailure(IllegalStateException(getString(R.string.analytics_import_busy)))
        }) {
            val result = runCatching {
                ImportedAnalyticsStore.validate(this, username, parsed.movements)
                if (useBridge) {
                    val accepted = HistoryPool.importAnalytics(
                        this,
                        username,
                        parsed.movements,
                        TwidgetStore.bridgeEndpoint(settings),
                    )
                    val current = TwidgetStore.currentStats(this, username)
                    TwidgetStore.saveStats(this, current.copy(history = accepted.history))
                } else {
                    TwidgetStore.importFollowerHistory(this, username, parsed.samples)
                }
                ImportedAnalyticsStore.saveVerified(this, username, parsed.movements)
                TwidgetWidget.updateAll(this)
            }
            runOnUiThread {
                if (generation != asyncGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { renderStep(STEP_SUCCESS) }
                    .onFailure(::showFailure)
            }
        }
    }

    private fun showFailure(error: Throwable) {
        val bridge = error as? BridgeImportException
        val local = error as? AnalyticsValidationException
        val csv = error as? AnalyticsCsvException
        val code = bridge?.code ?: local?.code ?: csv?.code.orEmpty()
        val expected = bridge?.expectedFollowers ?: local?.expectedFollowers ?: csv?.cachedFollowers
            ?: stats.followersCount.takeIf { stats.followersKnown }
        val detected = bridge?.detectedFollowers ?: local?.detectedFollowers ?: csv?.detectedFollowers
        findViewById<TextView>(R.id.import_failure_reason).text = when (code) {
            "analytics_follower_mismatch" -> getString(R.string.import_failure_range)
            "analytics_trend_mismatch" -> getString(R.string.analytics_import_trend_mismatch)
            "analytics_impossible_followers" -> getString(R.string.import_failure_impossible)
            "insufficient_trusted_history" -> getString(R.string.analytics_import_not_enough_history)
            "private_account_not_pooled" -> getString(R.string.analytics_import_private)
            "analytics_export_not_current" -> getString(R.string.import_failure_old_export)
            "analytics_date_gap", "invalid_analytics_rows" -> getString(R.string.import_failure_invalid_rows)
            "current_followers_unavailable", "profile_fetch_failed", "pool_http_error" -> getString(R.string.import_failure_connection)
            else -> if (bridge != null || error !is IllegalArgumentException) {
                getString(R.string.import_failure_default)
            } else {
                error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.import_failure_default)
            }
        }
        findViewById<TextView>(R.id.import_failure_help).text = getString(when (code) {
            "analytics_follower_mismatch", "analytics_trend_mismatch", "analytics_impossible_followers" -> R.string.import_failure_refresh_help
            "insufficient_trusted_history" -> R.string.import_failure_history_help
            "private_account_not_pooled" -> R.string.import_failure_private_help
            "current_followers_unavailable", "profile_fetch_failed", "pool_http_error" -> R.string.import_failure_connection_help
            else -> R.string.import_failure_file_help
        })
        val showCounts = code in setOf("analytics_follower_mismatch", "analytics_trend_mismatch") &&
            expected != null && detected != null && expected >= 0 && detected >= 0
        findViewById<View>(R.id.import_failure_counts).visibility = if (showCounts) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.import_failure_date).apply {
            val day = bridge?.comparisonDay ?: local?.comparisonDay
            visibility = if (day != null) View.VISIBLE else View.GONE
            text = day?.let {
                getString(R.string.import_failure_comparison_day, runCatching { formatDate(LocalDate.parse(it)) }.getOrDefault(it))
            }.orEmpty()
        }
        findViewById<TextView>(R.id.import_failure_detected_label).setText(
            if (code == "analytics_trend_mismatch") R.string.import_calculated_followers else R.string.import_csv_followers,
        )
        findViewById<TextView>(R.id.import_stored_value).text = expected
            ?.let(::formatCount)
            ?: getString(R.string.import_followers_not_cached)
        findViewById<TextView>(R.id.import_failure_detected_value).text = detected
            ?.let(::formatCount)
            ?: getString(R.string.import_followers_not_detected)
        findViewById<TextView>(R.id.import_failure_difference).text = if (showCounts) {
            getString(R.string.import_failure_difference, formatCount(kotlin.math.abs(expected!! - detected!!)))
        } else ""
        findViewById<ScrollView>(R.id.import_step_failure).scrollTo(0, 0)
        renderStep(STEP_FAILURE)
    }

    private fun renderStep(next: Int, animate: Boolean = true) {
        step = next
        val steps = mapOf(
            STEP_INTRO to R.id.import_step_intro,
            STEP_CONFIRM to R.id.import_step_confirm,
            STEP_LOADING to R.id.import_step_loading,
            STEP_SUCCESS to R.id.import_step_success,
            STEP_FAILURE to R.id.import_step_failure,
        )
        steps.forEach { (which, id) -> findViewById<View>(id).visibility = if (which == step) View.VISIBLE else View.GONE }
        findViewById<View>(R.id.import_root).setBackgroundResource(
            when (step) {
                STEP_SUCCESS -> R.drawable.import_success_bg
                STEP_FAILURE -> R.drawable.import_failure_bg
                else -> R.drawable.import_gradient_bg
            }
        )
        configureButtons()
        if (animate) steps[step]?.let { id ->
            findViewById<View>(id).apply {
                alpha = 0f
                translationY = 18f * resources.displayMetrics.density
                animate().alpha(1f).translationY(0f).setDuration(320)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
    }

    private fun configureButtons() {
        val primary = findViewById<AppCompatButton>(R.id.import_primary_button)
        val secondary = findViewById<AppCompatButton>(R.id.import_secondary_button)
        when (step) {
            STEP_INTRO, STEP_CONFIRM -> {
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.cancel)
                primary.visibility = View.VISIBLE
                primary.text = getString(R.string.import_action)
                primary.setTextColor(getColor(android.R.color.white))
                primary.setBackgroundResource(R.drawable.import_primary_button_bg)
                setTwoButtonMargins(secondary, primary)
            }
            STEP_LOADING -> {
                primary.visibility = View.GONE
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.cancel)
                setSingleButtonMargins(secondary)
            }
            STEP_FAILURE -> {
                secondary.visibility = View.VISIBLE
                secondary.text = getString(R.string.done)
                primary.visibility = View.VISIBLE
                primary.text = getString(R.string.import_try_another_file)
                primary.setTextColor(getColor(android.R.color.white))
                primary.setBackgroundResource(R.drawable.import_primary_button_bg)
                setTwoButtonMargins(secondary, primary)
            }
            STEP_SUCCESS -> {
                secondary.visibility = View.GONE
                primary.visibility = View.VISIBLE
                primary.text = getString(R.string.continue_button)
                primary.setTextColor(getColor(R.color.oneui_text_primary))
                primary.setBackgroundResource(R.drawable.onboarding_glass_button_bg)
                setSingleButtonMargins(primary)
            }
        }
    }

    private fun setTwoButtonMargins(left: View, right: View) {
        (left.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = dp(6); weight = 1f }
            .also { left.layoutParams = it }
        (right.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(6); weight = 1f }
            .also { right.layoutParams = it }
    }

    private fun setSingleButtonMargins(view: View) {
        (view.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = 0
            marginEnd = 0
            weight = 1f
        }.also { view.layoutParams = it }
    }

    private fun cancelAndFinish() {
        asyncGeneration++
        finish()
    }

    private fun formatRange(first: LocalDate, last: LocalDate): String =
        "${formatDate(first)} to ${formatDate(last)}"

    private fun formatDate(date: LocalDate): String {
        val month = date.month.getDisplayName(TextStyle.FULL, Locale.UK)
        return "${date.dayOfMonth}${ordinal(date.dayOfMonth)} $month, ${date.year}"
    }

    private fun ordinal(day: Int): String = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    private fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_USERNAME = "com.tjg.twidget.extra.ANALYTICS_IMPORT_USERNAME"
        private const val STEP_INTRO = 0
        private const val STEP_CONFIRM = 1
        private const val STEP_LOADING = 2
        private const val STEP_SUCCESS = 3
        private const val STEP_FAILURE = 4
    }
}
