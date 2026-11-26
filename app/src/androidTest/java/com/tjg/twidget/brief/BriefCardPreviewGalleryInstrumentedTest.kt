package com.tjg.twidget.brief

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.settings.BriefDebugActivity
import com.tjg.twidget.settings.BriefDebugFragment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BriefCardPreviewGalleryInstrumentedTest {
    @Test fun oneDebugEntryShowsAllDesignsWithoutInPageControlsOrProviderWork() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val account = TwidgetStore.settings(context).username
        assumeTrue(account.isNotBlank())
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val keys = listOf("debug_menu_unlocked", BriefCardPreviewParameters.PREFIX + "all")
        val saved = keys.associateWith { prefs.all[it] }
        val settings = context.getSharedPreferences("twidget_brief_settings", Context.MODE_PRIVATE)
        val enabled = settings.all["enabled"]
        val lock = BriefAiCoordinator::class.java.getDeclaredField("generationMutex").apply { isAccessible = true }.get(BriefAiCoordinator) as Mutex
        lock.lock()
        try {
            prefs.edit().putBoolean("debug_menu_unlocked", true).putBoolean(BriefCardPreviewParameters.PREFIX + "all", true).commit()
            ActivityScenario.launch<BriefDebugActivity>(android.content.Intent(context, BriefDebugActivity::class.java)).use { debug ->
                debug.onActivity { page ->
                    page.supportFragmentManager.executePendingTransactions()
                    val screen = (page.supportFragmentManager.findFragmentById(R.id.preference_fragment_container) as BriefDebugFragment).preferenceScreen
                    assertNotNull(screen.findPreference<androidx.preference.Preference>("preview_cards"))
                    assertNotNull(screen.findPreference<androidx.preference.Preference>("preview_parameters"))
                    listOf("components", "insights_preview", "chatgpt_card_preview", "review_preview", "launch", "blue_background", "posting_timing", "review_ui")
                        .forEach { assertNull("Consolidated $it", screen.findPreference<androidx.preference.Preference>(it)) }
                }
            }
            ActivityScenario.launch<TwidgetBriefActivity>(TwidgetBriefActivity.debugIntent(context, account, BriefDebugScenario.COMPONENTS)).use { gallery ->
                val readyBy = android.os.SystemClock.uptimeMillis() + 12_000
                var ready = false
                while (!ready && android.os.SystemClock.uptimeMillis() < readyBy) {
                    gallery.onActivity { page -> ready = page.findViewById<ViewGroup>(R.id.brief_cards).childCount > 0 }
                    if (!ready) Thread.sleep(30)
                }
                assertTrue("Gallery opens with the model provider locked", ready)
                gallery.onActivity { page ->
                    val views = page.findViewById<ViewGroup>(R.id.brief_cards).walk().toList()
                    val groups = views.mapNotNull { it.tag as? String }.filter { it.startsWith("brief_preview_group:") }
                    assertTrue(groups.size >= 15)
                    listOf("Posting streak", "Goals", "Tweet cards", "Tweet review states", "Top followers", "Posting guidance")
                        .forEach { assertTrue(groups.contains("brief_preview_group:$it")) }
                    assertFalse("All tweaks stay in Debug settings", views.any { it.isClickable })
                    assertEquals(View.GONE, page.findViewById<View>(R.id.brief_footer).visibility)
                    val guide = views.filterIsInstance<TextView>().first {
                        it.text.toString() == "Plan for the evening" && (it.parent as? View)?.background != null
                    }.parent as View
                    assertNotNull("The button owns its rounded fill and rim", guide.background)
                    for (width in listOf(162, 352, 600)) {
                        val px = (width * context.resources.displayMetrics.density).toInt()
                        guide.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        guide.layout(0, 0, px, guide.measuredHeight)
                        val bitmap = android.graphics.Bitmap.createBitmap(px, guide.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
                        guide.draw(android.graphics.Canvas(bitmap))
                        bitmap.recycle()
                        assertEquals(guide.background.bounds, guide.foreground.bounds)
                    }
                }
                prefs.edit().putBoolean(BriefCardPreviewParameters.PREFIX + "all", false).commit()
                for (widthDp in listOf(800, 360, 700, 320)) {
                    gallery.onActivity { page ->
                        val root = page.findViewById<View>(R.id.brief_root)
                        root.layoutParams = root.layoutParams.apply { width = (widthDp * context.resources.displayMetrics.density).toInt() }
                        page.onConfigurationChanged(android.content.res.Configuration(page.resources.configuration))
                    }
                    instrumentation.waitForIdleSync()
                    gallery.onActivity { page ->
                        assertFalse("Main Brief stays in its existing full-screen activity", page.isFinishing)
                        val cards = page.findViewById<android.widget.LinearLayout>(R.id.brief_cards)
                        assertEquals(if (widthDp >= 600) android.widget.LinearLayout.HORIZONTAL else android.widget.LinearLayout.VERTICAL, cards.orientation)
                        val root = page.findViewById<View>(R.id.brief_root)
                        val content = page.findViewById<View>(R.id.brief_content)
                        assertTrue("Content fits the live viewport after resizing to $widthDp", content.left >= 0 && content.right <= root.width)
                    }
                }
            }
        } finally {
            prefs.edit().apply { saved.forEach { (key, value) -> if (value is Boolean) putBoolean(key, value) else remove(key) } }.commit()
            settings.edit().apply { if (enabled is Boolean) putBoolean("enabled", enabled) else remove("enabled") }.commit()
            lock.unlock()
        }
    }

    private fun View.walk(): Sequence<View> = sequence {
        yield(this@walk)
        val group = this@walk as? ViewGroup ?: return@sequence
        for (index in 0 until group.childCount) yieldAll(group.getChildAt(index).walk())
    }
}
