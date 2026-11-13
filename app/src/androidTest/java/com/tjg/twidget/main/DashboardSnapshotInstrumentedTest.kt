package com.tjg.twidget.main

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.GridLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardSnapshotInstrumentedTest {
    @Test fun dataRefreshRetainsCardIdentityAndRecordsWarmBindingCost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val previous = prefs.all.toMap()
        val account = "audit_dashboard"
        try {
            prefs.edit().putBoolean("onboarded", true).putString("username", account)
                .putBoolean("refresh_on_launch", false).commit()
            TwidgetStore.saveStats(context, ProfileStats("Audit", account, 100, 20, 30, 40))
            TwidgetStore.saveDashboardCards(context, listOf("followers", "following", "follower_ratio", "daily_streak"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.selectedAccount = account
                    activity.dashboardBinder.bindContent()
                    val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
                    val wrappers = (0 until grid.childCount).map { grid.getChildAt(it) }
                    val content = wrappers.map { (it as android.view.ViewGroup).getChildAt(0) }
                    val timings = (1..10).map {
                        val start = SystemClock.elapsedRealtimeNanos()
                        activity.dashboardBinder.bindContent()
                        (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }
                    wrappers.forEachIndexed { index, wrapper ->
                        assertSame("Data refresh retains wrapper/focus", wrapper, grid.getChildAt(index))
                        assertSame("Metric and streak views remain bound", content[index],
                            (grid.getChildAt(index) as android.view.ViewGroup).getChildAt(0))
                    }
                    File(context.cacheDir, "dashboard-warm-bind-ms.txt").writeText(timings.toString())
                }
            }
        } finally {
            TwidgetStore.removeAccount(context, account)
            val editor = prefs.edit().clear()
            previous.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            editor.commit()
        }
    }
}
