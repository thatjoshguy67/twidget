package com.tjg.twidget.followers

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.SeslSeekBar
import androidx.appcompat.app.AlertDialog
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
    @Test fun searchDialogCombinesFiltersPreservesQueryAndReturnsToList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val username = "floating_filters_test"
        TopFollowersArchiveStore.seedFromTop(context, username, listOf(
            TopFollower("1", "zebra", "Zebra", 100_000, false, ""),
            TopFollower("2", "amy", "Amy", 10_000, false, ""),
            TopFollower("3", "alan", "Alan", 9_999, false, "")))
        val scale = FollowerThresholdScale(listOf(100_000, 10_000, 9_999))
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
                    assertNotNull("Shortcut opens the dialog from the collapsed FAB", a.searchFilters.dialog)
                    a.searchFilters.dismiss()
                    val search = a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    search.setQuery("amy", false)
                    assertFalse("Coordinator must allow the FAB shadow outside search bounds", (search.parent as ViewGroup).clipChildren)
                    assertEquals(View.GONE, search.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).visibility)
                    assertEquals(View.VISIBLE, search.seslGetOverflowMenuButton().visibility)
                    search.seslGetOverflowMenuButton().performClick()
                }
                settle()
                scenario.onActivity { a ->
                    val search = a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    assertNotNull(a.searchFilters.dialog)
                    // Opening a dialog may hide the IME; that is not an exit from search.
                    search.onImeVisibilityChanged(true); search.onImeVisibilityChanged(false)
                    assertTrue(search.isFloatingExpanded)
                    val slider = a.searchFilters.dialog!!.findViewById<SeslSeekBar>(R.id.follower_min_slider)!!
                    assertEquals("Three adaptive segments for this archive", 2, slider.max)
                    slider.progress = scale.position(10_000)
                    assertEquals("Segment selects its threshold", scale.position(10_000), slider.progress)
                    assertEquals(context.getString(R.string.floating_matching_followers, 1, 3), a.searchFilters.dialog!!.findViewById<TextView>(R.id.follower_min_results)!!.text.toString())
                    assertEquals("Pending selection is not applied yet", FollowerFilters(), a.searchFilters.filters)
                    a.searchFilters.dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                settle()
                scenario.onActivity { a ->
                    assertEquals("amy", a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).query.toString())
                    assertEquals(1, a.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                    a.searchFilters.show()
                }
                settle()
                scenario.onActivity { a ->
                    a.searchFilters.dialog!!.findViewById<RadioButton>(R.id.follower_sort_name)!!.performClick()
                    a.searchFilters.dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                settle(); scenario.recreate(); settle()
                scenario.onActivity { a ->
                    assertEquals(FollowerFilters(FollowerSort.NAME, 10_000), a.searchFilters.filters)
                    assertEquals("amy", a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).query.toString())
                    a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0,
                        KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON))
                }
                settle()
                scenario.onActivity { a ->
                    val dialog = a.searchFilters.dialog!!
                    assertTrue(dialog.findViewById<RadioButton>(R.id.follower_sort_name)!!.isChecked)
                    assertEquals(scale.position(10_000), dialog.findViewById<SeslSeekBar>(R.id.follower_min_slider)!!.progress)
                    dialog.findViewById<SeslSeekBar>(R.id.follower_min_slider)!!.progress = scale.position(100_000)
                    assertEquals(context.getString(R.string.floating_matching_followers, 0, 3), dialog.findViewById<TextView>(R.id.follower_min_results)!!.text.toString())
                    a.onBackPressedDispatcher.onBackPressed()
                    assertNull(a.searchFilters.dialog)
                    assertEquals("Back discards pending changes", FollowerFilters(FollowerSort.NAME, 10_000), a.searchFilters.filters)
                    assertTrue(a.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search).isFloatingExpanded)
                    a.searchFilters.show()
                }
                settle()
                scenario.onActivity { a ->
                    a.searchFilters.dialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                    assertEquals(0, a.searchFilters.dialog!!.findViewById<SeslSeekBar>(R.id.follower_min_slider)!!.progress)
                    a.searchFilters.dialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                    assertEquals("Cancel discards reset", FollowerFilters(FollowerSort.NAME, 10_000), a.searchFilters.filters)
                }
                settle()
                scenario.onActivity { a ->
                    a.searchFilters.show()
                    a.searchFilters.dialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                    a.searchFilters.dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
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
