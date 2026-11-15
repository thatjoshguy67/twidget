package com.tjg.twidget.followers

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.SearchView
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
class TopFollowersSesl95InstrumentedTest {
    @Test fun keyboardSearchFiltersAndSurvivesRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val account = "sesl95_search_test"
        TopFollowersArchiveStore.seedFromTop(context, account, (1..30).map {
            TopFollower("$it", "person$it", "Person $it", 1000L - it, false, "")
        })
        try {
            ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context, TopFollowersBrowseActivity::class.java)
                .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, account)).use { scenario ->
                instrumentation.waitForIdleSync()
                SystemClock.sleep(700)
                scenario.onActivity { activity ->
                    val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                    assertTrue(search is Sesl95SearchView)
                    assertFalse("Opening search must not steal focus", search.hasFocus())
                    assertFalse((search as Sesl95SearchView).isFloatingExpanded)
                    assertEquals((56 * activity.resources.displayMetrics.density).toInt(), search.width)
                    val ctrlF = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON)
                    assertTrue(activity.dispatchKeyEvent(ctrlF))
                    assertTrue(search.seslGetAutoCompleteView().hasFocus())
                    assertEquals("Expanded search has no internal back arrow", View.GONE, search.seslGetUpButton().visibility)
                    search.setQuery("person17", false)
                    assertNotEquals("Typing must restore ordinary hint colours", 0, search.seslGetAutoCompleteView().currentHintTextColor)
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
                scenario.onActivity { activity ->
                    assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                }
                scenario.recreate()
                instrumentation.waitForIdleSync()
                SystemClock.sleep(700)
                scenario.onActivity { activity ->
                    val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                    assertEquals("person17", search.query.toString())
                    assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                    assertTrue((search as Sesl95SearchView).isFloatingExpanded)
                    assertTrue(search.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
                    assertTrue(search.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
                    assertFalse(search.isFloatingExpanded)
                    assertEquals("", search.query.toString())
                    assertFalse(activity.isFinishing)
                    assertNull("The rejected toolbar overlay must be removed",
                        activity.window.decorView.findViewWithTag<View>("sesl95:start_first"))
                }
                scenario.onActivity { activity ->
                    val search = activity.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    search.expand(showKeyboard = false, animate = false)
                    search.setQuery("person17", false)
                    // IME consumes gesture Back; only its final visibility transition reaches us.
                    search.onImeVisibilityChanged(true)
                    search.onImeVisibilityChanged(false)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val search = activity.findViewById<Sesl95SearchView>(R.id.top_followers_browse_search)
                    assertFalse("IME dismissal must collapse the bar", search.isFloatingExpanded)
                    assertEquals("", search.query.toString())
                    assertFalse("Back must keep the follower list open", activity.isFinishing)
                }
            }
        } finally { TopFollowersArchiveStore.clear(context, account) }
    }
}
