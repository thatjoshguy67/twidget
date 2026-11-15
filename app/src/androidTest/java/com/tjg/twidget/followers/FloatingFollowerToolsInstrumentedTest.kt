package com.tjg.twidget.followers

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.ui.Sesl95SearchView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingFollowerToolsInstrumentedTest {
    @Test fun filtersComposeWithSearchRestoreAndCloseInPlace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val username = "floating_filters_test"
        TopFollowersArchiveStore.seedFromTop(context, username, listOf(
            TopFollower("1", "zebra", "Zebra", 100_000, false, ""),
            TopFollower("2", "amy", "Amy", 10_000, false, ""),
            TopFollower("3", "alan", "Alan", 9_999, false, "")))
        fun settle() { SystemClock.sleep(600); instrumentation.waitForIdleSync() }
        try {
            ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context, TopFollowersBrowseActivity::class.java)
                .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, username)).use { scenario ->
                settle()
                scenario.onActivity { a ->
                    a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).apply {
                        expand(false, false); setQuery("amy", false)
                    }
                    a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
                    assertTrue(a.findViewById<FollowerFilterCapsule>(R.id.follower_filters).expanded)
                    a.findViewById<View>(R.id.follower_min_10k).performClick()
                }
                settle()
                scenario.onActivity { a ->
                    assertEquals("Query is preserved when opening filters", "amy", a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).query.toString())
                    assertEquals(1, a.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                    a.findViewById<View>(R.id.follower_sort_name).performClick()
                }
                scenario.recreate(); settle()
                scenario.onActivity { a ->
                    val filters = a.findViewById<FollowerFilterCapsule>(R.id.follower_filters)
                    assertEquals(FollowerFilters(FollowerSort.NAME, 10_000), filters.filters)
                    assertTrue("Filter panel survives recreation", filters.expanded)
                    a.onBackPressedDispatcher.onBackPressed()
                    assertFalse(filters.expanded)
                    assertFalse(a.isFinishing)
                    a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON))
                    val search = a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    assertTrue(search.isFloatingExpanded)
                    repeat(4) { search.collapse(); search.expand(false) }
                    search.collapse()
                    repeat(4) { filters.setExpanded(true); filters.setExpanded(false) }
                }
                settle()
                scenario.onActivity { a ->
                    val search = a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    assertFalse(search.isFloatingExpanded)
                    assertEquals((56f * a.resources.displayMetrics.density).toInt(), search.width)
                    assertFalse(a.findViewById<FollowerFilterCapsule>(R.id.follower_filters).expanded)
                }
            }
        } finally { TopFollowersArchiveStore.clear(context, username) }
    }
}
