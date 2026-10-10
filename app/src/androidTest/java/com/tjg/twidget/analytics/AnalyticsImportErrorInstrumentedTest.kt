package com.tjg.twidget.analytics

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.bridge.BridgeImportException
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.AppAppearance
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalyticsImportErrorInstrumentedTest {
    @Test fun errorsExplainTheComparisonAndKeepActionsReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val saved = preferences.all
        val originalMode = AppAppearance.mode(context)
        // Seed only the account selection; no background sync or live requests.
        preferences.edit().putString("accounts", "[\"import_ui_test\"]").commit()
        try {
            for (mode in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { AppAppearance.setMode(context, mode) }
                ActivityScenario.launch<AnalyticsImportActivity>(
                    Intent(context, AnalyticsImportActivity::class.java)
                        .putExtra(AnalyticsImportActivity.EXTRA_USERNAME, "import_ui_test"),
                ).use { scenario ->
                    scenario.onActivity { activity ->
                        showFailure(activity, BridgeImportException(
                            status = 422,
                            code = "analytics_trend_mismatch",
                            expectedFollowers = 7_767,
                            detectedFollowers = 7_800,
                            message = "Pool HTTP 422: raw server JSON should not be displayed",
                            comparisonDay = "2026-09-19",
                        ))
                        assertEquals("7,767", activity.findViewById<TextView>(R.id.import_stored_value).text.toString())
                        assertEquals("7,800", activity.findViewById<TextView>(R.id.import_failure_detected_value).text.toString())
                        assertTrue(activity.findViewById<TextView>(R.id.import_failure_difference).text.contains("33"))
                        assertTrue(activity.findViewById<TextView>(R.id.import_failure_date).text.contains("19th September, 2026"))
                        assertEquals(activity.getString(R.string.import_calculated_followers),
                            activity.findViewById<TextView>(R.id.import_failure_detected_label).text.toString())
                        assertFalse(activity.findViewById<TextView>(R.id.import_failure_reason).text.contains("HTTP"))
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(400)
                    capture("analytics-import-error-$mode")
                    scenario.onActivity { activity ->
                        activity.findViewById<ScrollView>(R.id.import_step_failure).fullScroll(View.FOCUS_DOWN)
                    }
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(400)
                    scenario.onActivity { activity ->
                        val difference = activity.findViewById<View>(R.id.import_failure_difference)
                        val footer = activity.findViewById<View>(R.id.import_footer)
                        val bounds = Rect()
                        assertTrue("Last error detail must be reachable by scrolling", difference.getGlobalVisibleRect(bounds))
                        val footerBounds = Rect().also(footer::getGlobalVisibleRect)
                        assertTrue("Details must scroll above the fixed buttons", bounds.bottom <= footerBounds.top)
                        assertTrue(activity.findViewById<View>(R.id.import_primary_button).isShown)
                        assertTrue(activity.findViewById<View>(R.id.import_secondary_button).isShown)
                        for (id in listOf(R.id.import_primary_button, R.id.import_secondary_button)) {
                            val button = activity.findViewById<TextView>(id)
                            assertTrue("Button labels must fit at larger font sizes",
                                button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        }
                        showFailure(activity, AnalyticsCsvException(
                            4_192, null, "Technical reconstruction detail", code = "analytics_impossible_followers",
                        ))
                        assertEquals(View.GONE, activity.findViewById<View>(R.id.import_failure_counts).visibility)
                        assertTrue(activity.findViewById<TextView>(R.id.import_failure_help).text.contains("Refresh"))
                        showFailure(activity, BridgeImportException(503, "profile_fetch_failed", null, null, "Raw response"))
                        assertEquals(activity.getString(R.string.import_failure_connection),
                            activity.findViewById<TextView>(R.id.import_failure_reason).text.toString())
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppAppearance.setMode(context, originalMode) }
            preferences.edit().clear().apply {
                saved.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
        }
    }

    private fun showFailure(activity: AnalyticsImportActivity, error: Throwable) {
        AnalyticsImportActivity::class.java.getDeclaredMethod("showFailure", Throwable::class.java).apply {
            isAccessible = true
        }.invoke(activity, error)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
