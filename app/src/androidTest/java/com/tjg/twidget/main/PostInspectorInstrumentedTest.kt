package com.tjg.twidget.main

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.oneui.floatingdock.FloatingPaneLayout
import com.tjg.twidget.R
import com.tjg.twidget.analytics.AnalyticsClient
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.data.AccountPreferences
import com.tjg.twidget.data.TwidgetStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostInspectorInstrumentedTest {
    @Test fun cardTapMinimizeRestoreRecreationAndAccountBoundary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val keys = listOf("username", "accounts", "onboarded", "refresh_on_launch", "share_history")
        val previous = prefs.all.filterKeys { it in keys }
        val username = "floating_inspector_test"
        prefs.edit().putString("username", username).putString("accounts", "[\"$username\"]")
            .putBoolean("onboarded", true).putBoolean("refresh_on_launch", false).putBoolean("share_history", false).commit()
        val post = JSONObject().put("url", "https://x.com/$username/status/123456789")
            .put("text", "Inspector rendering fixture").put("views", 10000).put("likes", 200)
            .put("authorUserName", username).put("timestamp", System.currentTimeMillis())
        val json = JSONObject().put("userName", username).put("postsAnalyzed", 1)
            .put("best", post).put("worst", post).put("cachedAt", System.currentTimeMillis())
        val cache = AccountPreferences.open(context, "twidget_analytics")
        val key = "v7:${TwidgetStore.settings(context).dataSource}:$username"
        val previousCache = cache.getString(key, null)
        cache.edit().putString(key, json.toString()).commit()
        fun settle() { SystemClock.sleep(750); instrumentation.waitForIdleSync() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle()
                scenario.onActivity { activity ->
                    activity.analytics = NetworkResponseParsers.parseBridgeAnalytics(json)
                    val card = activity.postAnalyticsBinder.createGridCard(DashboardCardType.BEST_POST, username)
                    assertTrue(card.performClick())
                }
                settle()
                scenario.onActivity { activity ->
                    val pane = activity.findViewById<FloatingPaneLayout>(R.id.post_inspector_pane)
                    assertTrue("Card tap opens native inspector", pane.isShowing())
                    val content = pane.findViewById<View>(com.google.android.material.R.id.result_layout_content)
                    val visible = android.graphics.Rect()
                    assertTrue("Inspector content is visible", content.getGlobalVisibleRect(visible))
                    assertTrue(visible.width() > 100 && visible.height() > 100)
                    assertTrue(visible.bottom <= activity.window.decorView.height)
                    pane.findViewById<View>(R.id.post_inspector_minimize).performClick()
                }
                settle()
                scenario.onActivity { activity -> assertTrue(activity.findViewById<FloatingPaneLayout>(R.id.post_inspector_pane).isMinimizeView()) }
                scenario.recreate(); settle()
                scenario.onActivity { activity ->
                    PostAnalyticsInspector.attach(activity)
                }
                settle()
                scenario.onActivity { activity ->
                    val pane = activity.findViewById<FloatingPaneLayout>(R.id.post_inspector_pane)
                    assertEquals("Restored inspector remains visible", View.VISIBLE, pane.visibility)
                    assertTrue("Minimized state survives recreation", pane.isMinimizeView())
                    assertTrue("Inspector shortcut is dispatched", activity.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M, 0, KeyEvent.META_CTRL_ON)))
                }
                settle()
                scenario.onActivity { activity ->
                    val pane = activity.findViewById<FloatingPaneLayout>(R.id.post_inspector_pane)
                    assertFalse("Ctrl+M restores inspector", pane.isMinimizeView())
                    activity.selectedAccount = "another_account"
                    activity.window.decorView.requestLayout()
                }
                settle()
                scenario.onActivity { activity -> assertEquals(View.GONE, activity.findViewById<FloatingPaneLayout>(R.id.post_inspector_pane).visibility) }
            }
        } finally {
            cache.edit().apply { if (previousCache == null) remove(key) else putString(key, previousCache) }.commit()
            prefs.edit().apply {
                keys.forEach(::remove)
                previous.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value) } }
            }.commit()
        }
    }
}
