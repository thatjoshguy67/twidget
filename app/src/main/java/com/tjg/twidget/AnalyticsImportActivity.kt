package com.tjg.twidget

import android.content.Intent
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatButton
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
        (findViewById<ImageView>(R.id.import_spinner)?.drawable as? AnimatedVectorDrawable)?.stop()
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
                STEP_INTRO -> csvPicker.launch(arrayOf("text/csv", "text/*", "application/csv", "application/vnd.ms-excel"))
                STEP_CONFIRM -> startImport()
                STEP_SUCCESS, STEP_FAILURE -> finish()
            }
        }
    }

    private fun readCsv(uri: Uri) {
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
        findViewById<TextView>(R.id.import_detected_value).text = formatCount(parsed.samples.last().followers)
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
        val code = bridge?.code ?: local?.code.orEmpty()
        val expected = bridge?.expectedFollowers ?: local?.expectedFollowers
        val detected = bridge?.detectedFollowers ?: local?.detectedFollowers
        findViewById<TextView>(R.id.import_failure_reason).text = when (code) {
            "analytics_follower_mismatch", "analytics_trend_mismatch" -> getString(R.string.import_failure_range)
            "insufficient_trusted_history" -> getString(R.string.analytics_import_not_enough_history)
            "private_account_not_pooled" -> getString(R.string.analytics_import_private)
            else -> error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.import_failure_default)
        }
        findViewById<View>(R.id.import_failure_counts).visibility =
            if (expected != null && detected != null) View.VISIBLE else View.GONE
        expected?.let { findViewById<TextView>(R.id.import_stored_value).text = formatCount(it) }
        detected?.let { findViewById<TextView>(R.id.import_failure_detected_value).text = formatCount(it) }
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
        if (step == STEP_LOADING) {
            findViewById<ImageView>(R.id.import_spinner).apply {
                setImageResource(R.drawable.oneui_spinner)
                OneUiSpinner.loop(this)
            }
        }
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
            STEP_SUCCESS, STEP_FAILURE -> {
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
