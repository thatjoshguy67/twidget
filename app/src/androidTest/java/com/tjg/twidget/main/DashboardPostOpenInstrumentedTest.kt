package com.tjg.twidget.main

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.Context
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.core.NetworkResponseParsers
import com.tjg.twidget.data.AccountPreferences
import com.tjg.twidget.data.TwidgetStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardPostOpenInstrumentedTest {
    @Test fun cardTapOpensTweetDirectly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val keys = listOf("username", "accounts", "onboarded", "refresh_on_launch", "share_history")
        val previous = prefs.all.filterKeys { it in keys }
        val username = "post_open_test"
        prefs.edit().putString("username", username).putString("accounts", "[\"$username\"]")
            .putBoolean("onboarded", true).putBoolean("refresh_on_launch", false).putBoolean("share_history", false).commit()
        val post = JSONObject().put("url", "https://x.com/$username/status/123456789")
            .put("text", "Tweet launch fixture").put("views", 10000).put("likes", 200)
            .put("authorUserName", username).put("timestamp", System.currentTimeMillis())
        val json = JSONObject().put("userName", username).put("postsAnalyzed", 1)
            .put("best", post).put("worst", post).put("cachedAt", System.currentTimeMillis())
        val cache = AccountPreferences.open(context, "twidget_analytics")
        val key = "v7:${TwidgetStore.settings(context).dataSource}:$username"
        val previousCache = cache.getString(key, null)
        cache.edit().putString(key, json.toString()).commit()
        fun settle() { SystemClock.sleep(750); instrumentation.waitForIdleSync() }
        val opened = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                opened += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                settle()
                instrumentation.addMonitor(monitor)
                scenario.onActivity { activity ->
                    activity.analytics = NetworkResponseParsers.parseBridgeAnalytics(json)
                    for (type in listOf(DashboardCardType.BEST_POST, DashboardCardType.WORST_POST)) {
                        val before = opened.size
                        val card = activity.postAnalyticsBinder.createGridCard(type, username)
                        assertEquals(activity.getString(R.string.open_post), card.contentDescription)
                        assertTrue(card.performClick())
                        assertEquals("Card opens the tweet immediately", before + 1, opened.size)
                        assertEquals(post.getString("url"), opened.last().dataString)
                        assertEquals("com.twitter.android", opened.last().`package`)
                        assertEquals(0, opened.last().flags and Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.analytics = null
                    val empty = activity.postAnalyticsBinder.createGridCard(DashboardCardType.BEST_POST, username)
                    assertFalse("Empty cards do not open anything", empty.performClick())
                    assertEquals(2, opened.size)
                }
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            cache.edit().apply { if (previousCache == null) remove(key) else putString(key, previousCache) }.commit()
            prefs.edit().apply {
                keys.forEach(::remove)
                previous.forEach { (key, value) -> when (value) { is String -> putString(key, value); is Boolean -> putBoolean(key, value) } }
            }.commit()
        }
    }
}
