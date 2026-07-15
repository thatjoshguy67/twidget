package com.tjg.twidget.bridge

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.tjg.twidget.R
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug view of the requests and responses exchanged with the bridge. */
class DebugBridgeLogActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyEdgeToEdgeInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.bridge_log))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, DebugBridgeLogFragment())
                .commit()
        }
    }
}

class DebugBridgeLogFragment : InsetPreferenceFragment() {

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
        val entries = BridgeLog.entries(context)

        if (entries.isEmpty()) {
            screen.addPreference(Preference(context).apply {
                key = "log_empty"
                title = getString(R.string.bridge_log_empty)
                isSelectable = false
            })
            screen.addBottomInset()
            preferenceScreen = screen
            return
        }

        screen.addPreference(Preference(context).apply {
            key = "log_clear"
            title = SpannableString(getString(R.string.clear_log)).apply {
                setSpan(ForegroundColorSpan(context.getColor(R.color.metric_red)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setOnPreferenceClickListener {
                BridgeLog.clear(context)
                buildScreen()
                true
            }
        })

        entries.forEachIndexed { index, entry ->
            screen.addPreference(Preference(context).apply {
                key = "log_entry_$index"
                title = "${entry.method} ${displayPath(entry.url)}"
                summary = entrySummary(entry)
                setOnPreferenceClickListener {
                    showEntryDialog(entry)
                    true
                }
            })
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun entrySummary(entry: BridgeLog.Entry): String {
        val time = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(entry.timestamp))
        val outcome = when {
            entry.error != null -> getString(R.string.status_failed, entry.error.take(60))
            else -> "HTTP ${entry.code}"
        }
        val size = entry.responseBody?.let { " · ${sizeLabel(it.length)}" }.orEmpty()
        return "$outcome · ${entry.durationMs} ms$size · $time"
    }

    private fun showEntryDialog(entry: BridgeLog.Entry) {
        val context = requireContext()
        val details = buildString {
            appendLine("${entry.method} ${entry.url}")
            appendLine(entrySummary(entry))
            entry.requestBody?.let {
                appendLine()
                appendLine(getString(R.string.bridge_log_request))
                appendLine(it)
            }
            appendLine()
            appendLine(getString(R.string.bridge_log_response))
            append(entry.responseBody ?: entry.error ?: "—")
        }
        val text = TextView(context).apply {
            text = details
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(context.getColor(R.color.oneui_text_primary))
            setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(context)
            .setTitle("${entry.method} ${displayPath(entry.url)}")
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun displayPath(url: String): String = runCatching {
        val parsed = URL(url)
        parsed.path.ifBlank { "/" } + (parsed.query?.let { "?$it" }.orEmpty())
    }.getOrDefault(url)

    private fun sizeLabel(chars: Int): String =
        if (chars < 1024) "$chars B" else String.format(Locale.US, "%.1f KB", chars / 1024f)
}
