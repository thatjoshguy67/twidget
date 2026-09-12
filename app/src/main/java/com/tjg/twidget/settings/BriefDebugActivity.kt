package com.tjg.twidget.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefAiCoordinator
import com.tjg.twidget.brief.BriefAiDiagnostics
import com.tjg.twidget.brief.BriefDebugLog
import com.tjg.twidget.brief.BriefDebugScenario
import com.tjg.twidget.brief.BriefEngine
import com.tjg.twidget.brief.BriefNanoModelMode
import com.tjg.twidget.brief.BriefOnboardingActivity
import com.tjg.twidget.brief.TwidgetBriefActivity
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startSettingsSubActivity
import androidx.lifecycle.lifecycleScope
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class BriefDebugActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.brief_debug_title))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, BriefDebugFragment())
                .commit()
        }
    }
}

class BriefDebugLogActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.brief_debug_log_title))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, BriefDebugLogFragment())
                .commit()
        }
    }
}

class BriefDebugFragment : InsetPreferenceFragment() {
    private var aiDiagnostics: BriefAiDiagnostics? = null
    private var probeRunning = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = TwidgetStore.PREFS
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
        refreshAiDiagnostics()
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val username = TwidgetStore.settings(context).username
        val report = BriefEngine.inspect(context, username)
        val scenario = selectedScenario()

        screen.addPreference(category(R.string.brief_debug_preview_category))
        screen.addPreference(ListPreference(context).apply {
            key = KEY_SCENARIO
            title = getString(R.string.brief_debug_scenario)
            entries = BriefDebugScenario.entries.map(BriefDebugScenario::label).toTypedArray()
            entryValues = BriefDebugScenario.entries.map(BriefDebugScenario::storageId).toTypedArray()
            value = scenario.storageId
            summary = scenario.label
            setOnPreferenceChangeListener { _, newValue ->
                summary = BriefDebugScenario.fromStorageId(newValue as String).label
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_open_preview"
            title = getString(R.string.brief_debug_open_preview)
            summary = getString(R.string.brief_debug_open_preview_summary, scenario.label)
            setOnPreferenceClickListener {
                startActivity(TwidgetBriefActivity.debugIntent(context, username, selectedScenario()))
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_open_onboarding"
            title = getString(R.string.brief_debug_open_onboarding)
            summary = getString(R.string.brief_debug_open_onboarding_summary)
            isEnabled = username.isNotBlank()
            setOnPreferenceClickListener {
                startActivity(BriefOnboardingActivity.intent(context, username))
                true
            }
        })

        screen.addPreference(category(R.string.brief_debug_nano_category))
        val nanoModelMode = BriefAiCoordinator.nanoModelMode(context)
        screen.addPreference(ListPreference(context).apply {
            key = KEY_NANO_MODEL_MODE
            isPersistent = false
            title = getString(R.string.brief_debug_nano_model_mode)
            entries = BriefNanoModelMode.entries.map(BriefNanoModelMode::label).toTypedArray()
            entryValues = BriefNanoModelMode.entries.map(BriefNanoModelMode::storageId).toTypedArray()
            value = nanoModelMode.storageId
            summary = nanoModelMode.label
            setOnPreferenceChangeListener { _, newValue ->
                val selected = BriefNanoModelMode.fromStorageId(newValue as String)
                BriefAiCoordinator.setNanoModelMode(context, selected)
                summary = selected.label
                aiDiagnostics = null
                refreshAiDiagnostics(force = true)
                true
            }
        })
        val diagnostics = aiDiagnostics
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_nano_status"
            title = when {
                diagnostics == null -> getString(R.string.brief_debug_nano_checking)
                diagnostics.savedProvider == com.tjg.twidget.brief.BriefProviderUsed.LOCAL ->
                    getString(R.string.brief_debug_nano_in_use)
                else -> getString(R.string.brief_debug_nano_not_in_use)
            }
            summary = diagnostics?.let {
                buildString {
                    append("Mode ${it.mode} · feature ${it.localStatus}")
                    append("\nModel config ${it.nanoModelMode.label}")
                    it.resolvedNanoModelMode?.let { resolved ->
                        append("\nResolved model ${resolved.label}")
                    }
                    it.nanoProbeAttempts?.let { attempts -> append("\nProbe $attempts") }
                    append("\nML Kit runtime ${if (it.runtimePresent) "present" else "missing"} · cloud key ${if (it.cloudConfigured) "configured" else "missing"}")
                    if (it.localModelName != null || it.localTokenLimit != null) {
                        append("\nModel ${it.localModelName ?: "unknown"} · context ${it.localTokenLimit?.let { limit -> "$limit tokens" } ?: "unknown"}")
                    }
                    append("\nSaved Brief provider ${it.savedProvider}")
                    it.statusError?.let { error -> append("\nStatus error: $error") }
                }
            } ?: getString(R.string.brief_debug_nano_checking_summary)
            isSelectable = false
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_nano_last_attempt"
            title = getString(R.string.brief_debug_nano_last_attempt)
            summary = diagnostics?.let {
                buildString {
                    append(it.lastAttemptedProvider ?: "No provider attempt recorded")
                    if (it.lastAttemptAt > 0L) append(" · ${SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(it.lastAttemptAt))}")
                    it.lastOutcome?.let { outcome -> append("\nOutcome: $outcome") }
                    it.lastLocalDetail?.let { detail -> append("\nLocal detail: $detail") }
                    it.lastLocalFailure?.let { failure -> append("\nLocal failure: $failure") }
                }
            } ?: getString(R.string.brief_debug_nano_no_attempt)
            isSelectable = false
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_nano_refresh"
            title = getString(R.string.brief_debug_nano_refresh)
            summary = getString(R.string.brief_debug_nano_refresh_summary)
            isEnabled = !probeRunning
            setOnPreferenceClickListener {
                refreshAiDiagnostics(force = true)
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_nano_test"
            title = getString(R.string.brief_debug_nano_test)
            summary = getString(R.string.brief_debug_nano_test_summary)
            isEnabled = !probeRunning && username.isNotBlank()
            setOnPreferenceClickListener {
                runProviderTest(username)
                true
            }
        })

        screen.addPreference(category(R.string.brief_debug_inputs_category))
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_inputs"
            title = "@$username"
            summary = buildString {
                append("Followers ${format(report.followers)} · today ${signed(report.followersToday)} · week ${signed(report.followersWeek)}")
                append("\nPosts ${format(report.posts)} · history ${report.historySamples} samples")
                append("\nAnalytics ${if (report.analyticsCachedAt > 0) "cached" else "missing"} · follower scan ${report.followersScanned} accounts")
            }
            isSelectable = false
        })

        screen.addPreference(category(R.string.brief_debug_ranking_category))
        report.rankedCandidates.forEachIndexed { index, card ->
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_candidate_${card.id}"
                val selected = card.id in report.selectedIds
                val rank = card.rankingScore.takeIf { it >= 0 } ?: card.score
                title = "${index + 1}. $rank · ${card.type} · ${if (selected) "selected" else "omitted"}"
                summary = "${card.title}\n${card.body}"
                setOnPreferenceClickListener {
                    showText(card.title, "rank=$rank\nbase=${card.score}\ntype=${card.type}\nid=${card.id}\nselected=$selected\n\n${card.body}")
                    true
                }
            })
        }

        screen.addPreference(category(R.string.brief_debug_actions_category))
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_refresh"
            title = getString(R.string.brief_debug_refresh)
            summary = getString(R.string.brief_debug_refresh_summary)
            isEnabled = username.isNotBlank()
            setOnPreferenceClickListener {
                startActivity(TwidgetBriefActivity.refreshIntent(context, username))
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_force_rebuild"
            title = getString(R.string.brief_debug_force_rebuild)
            summary = getString(R.string.brief_debug_force_rebuild_summary)
            setOnPreferenceClickListener {
                BriefEngine.rebuild(context, username, force = true)
                Toast.makeText(context, R.string.brief_debug_rebuilt, Toast.LENGTH_SHORT).show()
                buildScreen()
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_full_report"
            title = getString(R.string.brief_debug_full_report)
            summary = getString(R.string.brief_debug_full_report_summary)
            setOnPreferenceClickListener {
                showText(getString(R.string.brief_debug_full_report), report.asText(), copyable = true)
                true
            }
        })

        val log = BriefDebugLog.entries(context)
        screen.addPreference(category(R.string.brief_debug_log_category))
        screen.addPreference(Preference(context).apply {
            key = "brief_debug_open_log"
            title = getString(R.string.brief_debug_log_title)
            summary = log.firstOrNull()?.let { latest ->
                resources.getQuantityString(
                    R.plurals.brief_debug_log_summary,
                    log.size,
                    log.size,
                    latest.event,
                    SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(latest.timestamp)),
                )
            } ?: getString(R.string.brief_debug_log_empty)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(
                    Intent(context, BriefDebugLogActivity::class.java),
                )
                true
            }
        })

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun showText(title: String, value: String, copyable: Boolean = false) {
        showDebugText(requireContext(), title, value, copyable)
    }

    private fun selectedScenario(): BriefDebugScenario = BriefDebugScenario.fromStorageId(
        preferenceManager.sharedPreferences?.getString(KEY_SCENARIO, null),
    )

    private fun refreshAiDiagnostics(force: Boolean = false) {
        if (probeRunning || (!force && aiDiagnostics != null)) return
        probeRunning = true
        buildScreen()
        viewLifecycleOwner.lifecycleScope.launch {
            aiDiagnostics = BriefAiCoordinator.diagnostics(
                requireContext(),
                TwidgetStore.settings(requireContext()).username,
            )
            probeRunning = false
            buildScreen()
        }
    }

    private fun runProviderTest(username: String) {
        if (probeRunning) return
        probeRunning = true
        buildScreen()
        viewLifecycleOwner.lifecycleScope.launch {
            val source = BriefEngine.rebuild(requireContext(), username, force = true)
            BriefAiCoordinator.enrich(requireContext(), source, force = true)
            aiDiagnostics = BriefAiCoordinator.diagnostics(requireContext(), username)
            probeRunning = false
            buildScreen()
        }
    }

    private fun category(titleRes: Int) = PreferenceCategory(requireContext()).apply {
        title = getString(titleRes)
        isIconSpaceReserved = false
    }

    private fun format(value: Long): String = java.text.NumberFormat.getIntegerInstance().format(value)
    private fun signed(value: Long): String = if (value > 0) "+${format(value)}" else format(value)

    companion object {
        private const val KEY_SCENARIO = "brief_debug_scenario"
        private const val KEY_NANO_MODEL_MODE = "brief_debug_nano_model_mode"
    }
}

class BriefDebugLogFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val log = BriefDebugLog.entries(context)

        if (log.isEmpty()) {
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_log_empty"
                title = getString(R.string.brief_debug_log_empty)
                isSelectable = false
            })
        } else {
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_clear_log"
                title = SpannableString(getString(R.string.clear_log)).apply {
                    setSpan(
                        ForegroundColorSpan(context.getColor(R.color.metric_red)),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                setOnPreferenceClickListener {
                    BriefDebugLog.clear(context)
                    buildScreen()
                    true
                }
            })
            log.forEachIndexed { index, entry ->
                screen.addPreference(Preference(context).apply {
                    key = "brief_debug_log_$index"
                    title = "${entry.event} · @${entry.username}"
                    summary = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
                        .format(Date(entry.timestamp))
                    setOnPreferenceClickListener {
                        showDebugText(context, entry.event, entry.report, copyable = true)
                        true
                    }
                })
            }
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }
}

private fun showDebugText(
    context: Context,
    title: String,
    value: String,
    copyable: Boolean = false,
) {
    val textView = TextView(context).apply {
        text = value
        typeface = android.graphics.Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(context.getColor(R.color.oneui_text_primary))
        setTextIsSelectable(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(textView) })
        .setNegativeButton(R.string.done, null)
        .apply {
            if (copyable) setPositiveButton(R.string.copy) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, value))
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        .show()
}
