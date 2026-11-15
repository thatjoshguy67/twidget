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
    @Test fun searchMenuCombinesFiltersPreservesQueryAndReturnsToList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val username = "floating_filters_test"
        TopFollowersArchiveStore.seedFromTop(context, username, listOf(
            TopFollower("1", "zebra", "Zebra", 100_000, false, ""),
            TopFollower("2", "amy", "Amy", 10_000, false, ""),
            TopFollower("3", "alan", "Alan", 9_999, false, "")))
        fun settle() { SystemClock.sleep(500); instrumentation.waitForIdleSync() }
        try {
            ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context, TopFollowersBrowseActivity::class.java)
                .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, username)).use { scenario ->
                settle()
                scenario.onActivity { a ->
                    assertNull("No standalone filter surface", a.findViewById<View>(R.id.follower_filters))
                    a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
                }
                settle()
                scenario.onActivity { a ->
                    assertNotNull("Shortcut opens the menu from the collapsed FAB", a.searchFilters.popup)
                    a.searchFilters.dismiss()
                    val search = a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    search.setQuery("amy", false)
                    assertEquals(View.GONE, search.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).visibility)
                    assertEquals(View.VISIBLE, search.seslGetOverflowMenuButton().visibility)
                    search.seslGetOverflowMenuButton().performClick()
                }
                settle()
                scenario.onActivity { a ->
                    val search = a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    assertNotNull(a.searchFilters.popup)
                    // Opening a popup may hide the IME; that is not an exit from search.
                    search.onImeVisibilityChanged(true); search.onImeVisibilityChanged(false)
                    assertTrue(search.isFloatingExpanded)
                    a.searchFilters.popup!!.menu.performIdentifierAction(R.id.follower_min_10k, 0)
                }
                settle()
                scenario.onActivity { a ->
                    assertEquals("amy", a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).query.toString())
                    assertEquals(1, a.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                    a.searchFilters.show()
                }
                settle()
                scenario.onActivity { a -> a.searchFilters.popup!!.menu.performIdentifierAction(R.id.follower_sort_name, 0) }
                scenario.recreate(); settle()
                scenario.onActivity { a ->
                    assertEquals(FollowerFilters(FollowerSort.NAME, 10_000), a.searchFilters.filters)
                    assertEquals("amy", a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).query.toString())
                    a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
                }
                settle()
                scenario.onActivity { a ->
                    val menu = a.searchFilters.popup!!.menu
                    assertTrue(menu.findItem(R.id.follower_sort_name).isChecked)
                    assertTrue(menu.findItem(R.id.follower_min_10k).isChecked)
                    a.onBackPressedDispatcher.onBackPressed()
                    assertNull(a.searchFilters.popup)
                    assertTrue(a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).isFloatingExpanded)
                    a.searchFilters.show()
                }
                settle()
                scenario.onActivity { a -> a.searchFilters.popup!!.menu.performIdentifierAction(R.id.follower_filter_reset, 0) }
                settle()
                scenario.onActivity { a ->
                    assertEquals(FollowerFilters(), a.searchFilters.filters)
                    assertEquals("Reset preserves the text query", "amy", a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).query.toString())
                    a.onBackPressedDispatcher.onBackPressed()
                    assertFalse(a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).isFloatingExpanded)
                    assertFalse(a.isFinishing)
                }
                settle()
                scenario.onActivity { a -> assertEquals(3, a.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount) }
            }
        } finally { TopFollowersArchiveStore.clear(context, username) }
    }
}
