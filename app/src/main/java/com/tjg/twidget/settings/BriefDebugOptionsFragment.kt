package com.tjg.twidget.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.tjg.twidget.R
import com.tjg.twidget.brief.*
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.core.AppLocales
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.TwidgetFonts
import com.tjg.twidget.ui.startSettingsSubActivity
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class BriefDebugPage(val title: Int) {
    MAIN(R.string.debug_brief_home), AI(R.string.debug_brief_ai), RANKING(R.string.debug_brief_ranking),
    REVIEW_UI(R.string.debug_review_ui), REVIEW_ENGINE(R.string.debug_review_engine), REVIEW_AI(R.string.debug_review_ai), REVIEW_LOG(R.string.debug_review_log);
    companion object {
        const val EXTRA = "brief_debug_page"
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: MAIN
    }
}

open class BriefDebugOptionsFragment : InsetPreferenceFragment() {
    private val page get() = BriefDebugPage.from(arguments?.getString(BriefDebugPage.EXTRA))
    private var diagnostics: BriefAiDiagnostics? = null
    private var busy = false
    private var reviewSelection: String? = null
    private var testResult: String? = null
    private val username get() = TwidgetStore.settings(requireContext()).username

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = TwidgetStore.PREFS
        reviewSelection = savedInstanceState?.getString("selection")
        testResult = savedInstanceState?.getString("test_result")
        buildScreen()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selection", reviewSelection); outState.putString("test_result", testResult)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume(); buildScreen()
        if (page == BriefDebugPage.AI && diagnostics == null) refreshNano()
    }

    private fun buildScreen() {
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        when (page) {
            BriefDebugPage.MAIN -> main(screen)
            BriefDebugPage.AI -> ai(screen)
            BriefDebugPage.RANKING -> ranking(screen)
            BriefDebugPage.REVIEW_UI -> reviewUi(screen)
            BriefDebugPage.REVIEW_ENGINE -> reviewEngine(screen)
            BriefDebugPage.REVIEW_AI -> reviewAi(screen)
            BriefDebugPage.REVIEW_LOG -> reviewLog(screen)
        }
        screen.addBottomInset(); preferenceScreen = screen
    }

    private fun main(screen: PreferenceScreen) {
        val context = requireContext()
        group(screen, R.string.debug_ui_state)
        row(screen, "components", R.string.debug_component_library, enabled = username.isNotBlank()) {
            startActivity(TwidgetBriefActivity.debugIntent(context, username, BriefDebugScenario.COMPONENTS))
        }
        row(screen, "onboarding", R.string.debug_show_onboarding, enabled = username.isNotBlank()) { startActivity(BriefOnboardingActivity.intent(context, username)) }
        row(screen, "review_preview", R.string.debug_show_review_preview) { preview() }
        gap(screen)
        screen.addPreference(ListPreference(context).apply {
            key = KEY_SCENARIO; title = getString(R.string.debug_preview_state)
            entries = BriefDebugScenario.entries.filterNot { it == BriefDebugScenario.COMPONENTS }.map { it.label(context) }.toTypedArray()
            entryValues = BriefDebugScenario.entries.filterNot { it == BriefDebugScenario.COMPONENTS }.map { it.storageId }.toTypedArray()
            value = scenario().storageId; summary = scenario().label(context)
            setOnPreferenceChangeListener { _, value ->
                summary = BriefDebugScenario.fromStorageId(value as String).label(context); true
            }
        })
        row(screen, "launch", R.string.debug_launch_preview, arrow = true, enabled = username.isNotBlank()) {
            startActivity(TwidgetBriefActivity.debugIntent(context, username, scenario()))
        }
        group(screen, R.string.debug_ai_diagnostics)
        row(screen, "ai", R.string.debug_view_ai) { open(BriefDebugPage.AI) }
        group(screen, R.string.debug_tweet_reviews)
        row(screen, "review_ui", R.string.debug_review_ui) { open(BriefDebugPage.REVIEW_UI) }
        row(screen, "review_engine", R.string.debug_review_engine) { open(BriefDebugPage.REVIEW_ENGINE) }
        row(screen, "review_ai", R.string.debug_review_ai) { open(BriefDebugPage.REVIEW_AI) }
        row(screen, "review_log", R.string.debug_review_log) { open(BriefDebugPage.REVIEW_LOG) }
        group(screen, R.string.debug_engine)
        val report = BriefEngine.inspect(context, username)
        row(screen, "inputs", "@$username", getString(R.string.l10n_debug_inputs, AppLocales.integer(report.followers), AppLocales.integer(report.followersToday), AppLocales.integer(report.followersWeek), AppLocales.integer(report.posts), AppLocales.integer(report.historySamples.toLong()), AppLocales.integer(report.followersScanned.toLong()))) {
            show(getString(R.string.brief_debug_full_report), report.asText(context))
        }
        gap(screen)
        row(screen, "ranking", R.string.debug_view_ranking) { open(BriefDebugPage.RANKING) }
        row(screen, "engine_log", R.string.debug_view_engine_log) {
            requireActivity().startSettingsSubActivity(Intent(context, BriefDebugLogActivity::class.java))
        }
        gap(screen)
        row(screen, "rebuild", R.string.debug_rebuild, enabled = username.isNotBlank()) {
            work { withContext(Dispatchers.IO) { BriefEngine.rebuild(context, username, force = true) }; Toast.makeText(context, R.string.brief_debug_rebuilt, Toast.LENGTH_SHORT).show() }
        }
        row(screen, "regenerate", R.string.debug_regenerate_brief, enabled = username.isNotBlank()) { startActivity(TwidgetBriefActivity.refreshIntent(context, username)) }
    }

    private fun ai(screen: PreferenceScreen) {
        val context = requireContext()
        val mode = BriefAiCoordinator.nanoModelMode(context)
        screen.addPreference(ListPreference(context).apply {
            key = "nano_config"; title = getString(R.string.debug_nano_config); isPersistent = false
            isEnabled = !busy
            entries = BriefNanoModelMode.entries.map { it.localizedLabel(context) }.toTypedArray()
            entryValues = BriefNanoModelMode.entries.map { it.storageId }.toTypedArray()
            value = mode.storageId; summary = mode.localizedLabel(context)
            setOnPreferenceChangeListener { _, value ->
                BriefAiCoordinator.setNanoModelMode(context, BriefNanoModelMode.fromStorageId(value as String)); diagnostics = null
                refreshNano(); true
            }
        })
        row(screen, "refresh_nano", R.string.debug_refresh_nano) { refreshNano() }
        row(screen, "cloud_config", R.string.debug_cloud_config, cloudConfig()) { show(getString(R.string.debug_cloud_config), cloudConfig()) }
        row(screen, "refresh_cloud", R.string.debug_refresh_cloud) { buildScreen() }
        gap(screen)
        val state = diagnostics?.let { getString(R.string.l10n_debug_nano_state, it.localStatus, it.mode, it.localModelName ?: getString(R.string.coach_ai_unknown), it.localTokenLimit?.let { n -> AppLocales.integer(n.toLong()) } ?: getString(R.string.coach_ai_unknown)) }
            ?: getString(R.string.brief_debug_nano_checking_summary)
        row(screen, "nano_state", R.string.debug_nano_state, state, copy = true) { show(getString(R.string.debug_nano_state), diagnostics.toString()) }
        val attempt = diagnostics?.let { getString(R.string.l10n_debug_attempt, date(it.lastAttemptAt), it.lastOutcome ?: getString(R.string.brief_debug_nano_no_attempt)) } ?: getString(R.string.brief_debug_nano_no_attempt)
        row(screen, "last_attempt", R.string.debug_last_attempt, attempt, copy = true) { show(getString(R.string.debug_last_attempt), diagnostics.toString()) }
        gap(screen)
        row(screen, "diagnose_provider", R.string.brief_debug_diagnose_generation,
            getString(R.string.brief_debug_diagnose_generation_summary), enabled = username.isNotBlank()) {
            work {
                val source = withContext(Dispatchers.IO) { BriefEngine.rebuild(context, username, force = true) }
                BriefAiCoordinator.diagnoseGeneration(context, source)
                diagnostics = BriefAiCoordinator.diagnostics(context, username)
                show(getString(R.string.brief_debug_diagnosis_report), diagnostics?.lastDetailedReport.orEmpty())
            }
        }
        row(screen, "test_provider", R.string.brief_debug_nano_test, getString(R.string.brief_debug_nano_test_summary), enabled = username.isNotBlank()) {
            work {
                val source = withContext(Dispatchers.IO) { BriefEngine.rebuild(context, username, force = true) }
                BriefAiCoordinator.enrich(context, source, force = true)
                diagnostics = BriefAiCoordinator.diagnostics(context, username)
            }
        }
        val report = diagnostics?.lastDetailedReport
        row(screen, "diagnosis_report", R.string.brief_debug_diagnosis_report,
            report?.lineSequence()?.firstOrNull() ?: getString(R.string.brief_debug_no_diagnosis), copy = true,
            enabled = !report.isNullOrBlank()) {
            show(getString(R.string.brief_debug_diagnosis_report), report.orEmpty())
        }
    }

    private fun ranking(screen: PreferenceScreen) {
        val report = BriefEngine.inspect(requireContext(), username)
        row(screen, "copy_ranking", R.string.debug_copy_results, copy = true) { copy(report.asText(requireContext())) }
        report.rankedCandidates.forEachIndexed { index, card ->
            gap(screen)
            val rank = card.rankingScore.takeIf { it >= 0 } ?: card.score
            row(screen, "rank_${card.id}", "$rank · ${card.type}", "${card.title}\n${card.body}") {
                show(card.title, getString(R.string.l10n_debug_rank_detail, AppLocales.integer(rank.toLong()), AppLocales.integer(card.score.toLong()), yes(card.id in report.selectedIds), card.id, card.body))
            }.apply { icon = RankDrawable(requireContext(), index + 1); isIconSpaceReserved = true }
        }
    }

    private fun reviewUi(screen: PreferenceScreen) {
        val choices = BriefReviewDebugFixtures.scenarios(requireContext())
        screen.addPreference(ListPreference(requireContext()).apply {
            key = KEY_REVIEW_SCENARIO; title = getString(R.string.debug_review_state)
            entries = choices.map { it.second }.toTypedArray(); entryValues = choices.map { it.first }.toTypedArray()
            value = reviewScenario(); summary = choices.firstOrNull { it.first == value }?.second
            setOnPreferenceChangeListener { _, next -> summary = choices.firstOrNull { it.first == next }?.second; true }
        })
        row(screen, "review_preview", R.string.debug_launch_preview, getString(R.string.debug_synthetic_note), arrow = true) { preview() }
        gap(screen)
        row(screen, "review_library", R.string.debug_open_review_library) {
            requireActivity().startSettingsSubActivity(BriefReviewListActivity.intent(requireContext(), username))
        }
    }

    private fun selected(screen: PreferenceScreen): BriefReviewTarget? {
        val choices = BriefReviewDebug.choices(requireContext(), username)
        fun id(choice: BriefReviewDebug.Choice) = choice.target.postUrl ?: "draft:${choice.target.draftId}"
        val current = choices.firstOrNull { id(it) == reviewSelection } ?: choices.firstOrNull()
        reviewSelection = current?.let(::id)
        screen.addPreference(ListPreference(requireContext()).apply {
            key = "review_subject"; title = getString(R.string.debug_review_subject); isPersistent = false
            entries = choices.map { it.label }.toTypedArray(); entryValues = choices.map(::id).toTypedArray()
            value = reviewSelection; summary = current?.label ?: getString(R.string.debug_no_review_subjects)
            isEnabled = choices.isNotEmpty() && !busy
            setOnPreferenceChangeListener { _, next -> reviewSelection = next as String; buildScreen(); true }
        })
        return current?.target
    }

    private fun reviewEngine(screen: PreferenceScreen) {
        val target = selected(screen)
        gap(screen)
        row(screen, "review_evidence", R.string.debug_review_evidence, getString(R.string.debug_review_evidence_summary), enabled = target != null) {
            target?.let { show(getString(R.string.debug_review_evidence), BriefReviewDebug.evidence(requireContext(), it)) }
        }
        row(screen, "review_cache", R.string.debug_review_cache, enabled = target != null) {
            target?.let { show(getString(R.string.debug_review_cache), BriefReviewDebug.cache(requireContext(), it)) }
        }
        row(screen, "review_checks", R.string.debug_review_checks, getString(R.string.debug_review_checks_summary)) { show(getString(R.string.debug_review_checks), BriefReviewDebug.checks(requireContext())) }
        gap(screen)
        row(screen, "open_review", R.string.review_view, enabled = target != null) { target?.let { openReview(it, false) } }
        row(screen, "regenerate_review", R.string.debug_regenerate_review, getString(R.string.debug_regenerate_review_summary), enabled = target != null) { target?.let { openReview(it, true) } }
    }

    private fun reviewAi(screen: PreferenceScreen) {
        row(screen, "review_provider", R.string.debug_review_route, getString(R.string.l10n_debug_review_policy, cloudConfig(), BriefSettingsStore.provider(requireContext()), BriefReviewPolicy.blocked(BriefSettingsStore.provider(requireContext()), BriefSettingsStore.cloudApiKey(requireContext()).isNotBlank()) ?: getString(R.string.l10n_debug_ready_cloud))) {
            requireActivity().startSettingsSubActivity(Intent(requireContext(), BriefSettingsActivity::class.java))
        }
        gap(screen)
        val target = selected(screen)
        row(screen, "review_test", R.string.debug_test_review_provider, getString(R.string.debug_test_review_provider_summary), enabled = target != null) {
            val context = requireContext()
            work {
                val subject = target?.let { withContext(Dispatchers.IO) { BriefReviewDebug.subject(context, it) } }
                if (subject == null) { testResult = getString(R.string.l10n_debug_source_unavailable); return@work }
                val started = SystemClock.elapsedRealtime()
                val result = BriefAiCoordinator.coach(context, subject.input)
                testResult = "${SystemClock.elapsedRealtime() - started} ms\n" + when (result) {
                    is BriefCoachGeneration.Success -> getString(R.string.l10n_debug_test_success, yes(result.cloud), AppLocales.integer(result.feedback.suggestions.size.toLong()), AppLocales.integer(result.inspectedMediaIds.size.toLong()), AppLocales.integer(subject.input.media.size.toLong()), yes(result.completeText))
                    is BriefCoachGeneration.Failure -> getString(R.string.l10n_debug_failure, result.reason)
                    else -> getString(R.string.l10n_debug_no_response)
                }
                BriefReviewDebugLog.record(context, "PROVIDER_TEST", JSONObject().put("outcome", testResult))
                show(getString(R.string.debug_review_test_result), testResult.orEmpty())
            }
        }
        gap(screen)
        row(screen, "review_test_result", R.string.debug_review_test_result, testResult ?: getString(R.string.brief_debug_nano_no_attempt), copy = true) {
            show(getString(R.string.debug_review_test_result), testResult ?: getString(R.string.brief_debug_nano_no_attempt))
        }
        row(screen, "review_log", R.string.debug_review_log) { open(BriefDebugPage.REVIEW_LOG) }
    }

    private fun reviewLog(screen: PreferenceScreen) {
        val entries = BriefReviewDebugLog.entries(requireContext())
        row(screen, "copy_review_log", R.string.debug_copy_results, copy = true) { copy(entries.toString(2)) }
        row(screen, "clear_review_log", R.string.clear_log) { BriefReviewDebugLog.clear(requireContext()); buildScreen() }
        for (index in 0 until entries.length()) {
            gap(screen)
            val entry = entries.getJSONObject(index)
            row(screen, "review_log_$index", entry.getString("event"), date(entry.optLong("at"))) { show(entry.getString("event"), entry.toString(2)) }
        }
        if (entries.length() == 0) row(screen, "empty_review_log", R.string.debug_review_log_empty, enabled = false) {}
    }

    private fun scenario() = BriefDebugScenario.fromStorageId(preferenceManager.sharedPreferences?.getString(KEY_SCENARIO, null))
    private fun reviewScenario() = preferenceManager.sharedPreferences?.getString(KEY_REVIEW_SCENARIO, "quoted") ?: "quoted"
    private fun preview() = startActivity(BriefReviewDebugActivity.intent(requireContext(), reviewScenario()))
    private fun openReview(target: BriefReviewTarget, force: Boolean) = requireActivity().startSettingsSubActivity(
        BriefTweetReviewActivity.intent(requireContext(), target).putExtra(BriefTweetReviewActivity.EXTRA_DEBUG_FORCE, force))
    private fun open(page: BriefDebugPage) = requireActivity().startSettingsSubActivity(
        Intent(requireContext(), BriefDebugActivity::class.java).putExtra(BriefDebugPage.EXTRA, page.name))
    private fun refreshNano() = work { diagnostics = BriefAiCoordinator.diagnostics(requireContext(), username) }
    private fun yes(value: Boolean) = getString(if (value) R.string.l10n_debug_yes else R.string.l10n_debug_no)
    private fun cloudConfig() = getString(R.string.l10n_debug_cloud_config,
        GeminiCloudBriefProvider.ENDPOINT.substringAfter("/models/").substringBefore(':'),
        getString(if (BriefSettingsStore.cloudApiKey(requireContext()).isNotBlank()) R.string.l10n_debug_key_present else R.string.l10n_debug_key_missing))
    private fun show(title: String, body: String) = showDebugText(requireContext(), title, body, copyable = true)
    private fun date(at: Long) = if (at > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at)) else getString(R.string.brief_debug_nano_no_attempt)
    private fun copy(value: String) {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Twidget debug", value))
        Toast.makeText(requireContext(), R.string.copied, Toast.LENGTH_SHORT).show()
    }
    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; buildScreen()
        viewLifecycleOwner.lifecycleScope.launch {
            try { block() }
            catch (error: Exception) { if (error is CancellationException) throw error; testResult = error.javaClass.simpleName; show(getString(R.string.debug_test_failed), testResult.orEmpty()) }
            finally { busy = false; if (isAdded && view != null) buildScreen() }
        }
    }
    private fun group(screen: PreferenceScreen, title: Int) { screen.addPreference(PreferenceCategory(requireContext()).apply { setTitle(title); isIconSpaceReserved = false }) }
    private fun gap(screen: PreferenceScreen) { screen.addPreference(InsetPreferenceCategory(requireContext())) }
    private fun row(screen: PreferenceScreen, key: String, title: Int, summary: String? = null, copy: Boolean = false, arrow: Boolean = false,
        enabled: Boolean = true, click: () -> Unit) = row(screen, key, getString(title), summary, copy, arrow, enabled, click)
    private fun row(screen: PreferenceScreen, key: String, title: String, summary: String? = null, copy: Boolean = false, arrow: Boolean = false,
        enabled: Boolean = true, click: () -> Unit) = Preference(requireContext()).apply {
        this.key = key; this.title = title; this.summary = summary; isPersistent = false; isIconSpaceReserved = false; isEnabled = enabled && !busy
        if (copy) widgetLayoutResource = R.layout.preference_widget_debug_copy
        if (arrow) widgetLayoutResource = R.layout.preference_widget_debug_arrow
        setOnPreferenceClickListener { click(); true }; screen.addPreference(this)
    }
    companion object { private const val KEY_SCENARIO = "brief_debug_scenario"; private const val KEY_REVIEW_SCENARIO = "brief_debug_review_scenario" }
}

private class RankDrawable(context: Context, private val rank: Int) : Drawable() {
    private val size = (24 * context.resources.displayMetrics.density).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.oneui_text_primary); textSize = 18 * context.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER; typeface = TwidgetFonts.forApp(context, 700)
    }
    override fun draw(canvas: Canvas) { canvas.drawText(rank.toString(), bounds.exactCenterX(), bounds.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
}
