package com.tjg.twidget.followers

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.ui.AppAppearance
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopFollowersSearchInstrumentedTest {
    @Test fun keyboardAnimationDoesNotShrinkTheFollowerViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val username = "ime_viewport_fixture"
        TopFollowersArchiveStore.seedFromTop(context,username,(1..30).map {
            TopFollower("$it","keyboard$it","Follower $it",1000L-it,false,"")
        })
        try {
            ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context,TopFollowersBrowseActivity::class.java)
                .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME,username)).use { scenario ->
                instrumentation.waitForIdleSync()
                SystemClock.sleep(600)
                var height = 0
                val heights = mutableListOf<Int>()
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                    height = list.height
                    list.viewTreeObserver.addOnPreDrawListener { heights += list.height; true }
                    activity.findViewById<com.tjg.twidget.ui.Sesl95SearchView>(R.id.top_followers_browse_search).expand()
                }
                SystemClock.sleep(1200)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.top_followers_browse_root)
                    val insets = requireNotNull(ViewCompat.getRootWindowInsets(root))
                    assertTrue("Keyboard is actually visible",insets.isVisible(WindowInsetsCompat.Type.ime()))
                    assertEquals("IME never becomes root padding",0,root.paddingBottom)
                    assertTrue("Every animation frame keeps a full-height list",heights.isNotEmpty() && heights.all { it == height })
                    val search=activity.findViewById<View>(R.id.top_followers_browse_search)
                    val location=IntArray(2).also(search::getLocationInWindow)
                    assertTrue("Search remains above the keyboard",location[1]+search.height <= activity.window.decorView.height-insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                }
            }
        } finally { TopFollowersArchiveStore.clear(context,username) }
    }

    @Test fun largeArchiveKeepsOnlyVisibleRowsAttached() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val username = "large_archive_layout_test"
        TopFollowersArchiveStore.seedFromTop(context, username, (1..8000).map {
            TopFollower("$it", "large$it", "Follower $it", 8000L - it, false, "")
        })
        try {
            ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context, TopFollowersBrowseActivity::class.java)
                .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, username)).use { scenario ->
                instrumentation.waitForIdleSync()
                SystemClock.sleep(600)
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                    assertEquals(8000, list.adapter!!.itemCount)
                    assertTrue("Large archives must recycle offscreen rows", list.childCount in 1..30)
                    assertTrue("Viewport must remain bounded by the window", list.height <= activity.window.decorView.height)
                    assertFalse(activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).seslGetGoToTopView()!!.isShown)
                    (list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).scrollToPositionWithOffset(3456, 0)
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(600)
                scenario.onActivity { activity ->
                    activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).seslShowGoToTop()
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(100)
                scenario.onActivity { activity ->
                    val button = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).seslGetGoToTopView()!!
                    val search = activity.findViewById<View>(R.id.top_followers_browse_search)
                    assertEquals(View.VISIBLE, button.visibility)
                    val buttonBounds = Rect().also(button::getGlobalVisibleRect)
                    val searchBounds = Rect().also(search::getGlobalVisibleRect)
                    assertTrue("Return button sits above search", buttonBounds.bottom < searchBounds.top)
                    val decor = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                    val controller = RecyclerView::class.java.getDeclaredField("mGoToTopController").apply { isAccessible = true }.get(decor)
                    val hitRect = Rect(controller.javaClass.getDeclaredField("mGoToTopRect").apply { isAccessible = true }.get(controller) as Rect)
                    val scroller = RecyclerView::class.java.getDeclaredField("mFastScroller").apply { isAccessible = true }.get(decor)
                    val preview = scroller.javaClass.getDeclaredField("mPrimaryText").apply { isAccessible = true }.get(scroller) as android.widget.TextView
                    val first = (decor.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition()
                    assertEquals(3456, first)
                    assertEquals("3457", preview.text.toString())
                    assertFalse("Native button hit area must exist", hitRect.isEmpty)
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(now, now + action * 20L, action,
                            hitRect.exactCenterX(), hitRect.exactCenterY(), 0)
                        controller.javaClass.getMethod("onTouchEvent", android.view.MotionEvent::class.java).invoke(controller, event)
                        event.recycle()
                    }
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(1500)
                scenario.onActivity { activity ->
                    assertFalse(activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).canScrollVertically(-1))
                    assertFalse(activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).seslGetGoToTopView()!!.isShown)
                }
            }
        } finally { TopFollowersArchiveStore.clear(context, username) }
    }

    @Test fun checkpointBubbleFollowsAllAppFontChoices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val original = AppAppearance.font(context)
        try {
            for (font in AppAppearance.Font.entries) {
                AppAppearance.setFont(context, font)
                ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context, TopFollowersBrowseActivity::class.java)
                    .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, "font_test")).use { scenario ->
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                        val scroller = RecyclerView::class.java.getDeclaredField("mFastScroller")
                            .apply { isAccessible = true }.get(list)
                        val preview = scroller.javaClass.getDeclaredField("mPrimaryText")
                            .apply { isAccessible = true }.get(scroller) as android.widget.TextView
                        assertEquals(com.tjg.twidget.ui.TwidgetFonts.forApp(context, 400), preview.typeface)
                    }
                }
            }
        } finally { AppAppearance.setFont(context, original) }
    }

    @Test fun bottomSearchFiltersRestoresAndClearsInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val username = "native_search_test"
        val originalMode = AppAppearance.mode(context)
        TopFollowersArchiveStore.clear(context, username)
        TopFollowersArchiveStore.seedFromTop(context, username, (1..30).map {
            TopFollower("$it", "follower$it", "Follower $it", 10000L - it, false, "")
        })
        fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(500) }
        fun assertShortSurface(activity: TopFollowersBrowseActivity) {
            val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
            val bitmap = Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888)
            list.draw(android.graphics.Canvas(bitmap))
            assertEquals("Short results leave the area below their surface transparent", 0,
                android.graphics.Color.alpha(bitmap.getPixel(list.width / 2, list.height - 1)))
            bitmap.recycle()
        }
        fun capture(name: String) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            for (mode in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { AppAppearance.setMode(context, mode) }
                var compactWidth = 0
                ActivityScenario.launch<TopFollowersBrowseActivity>(Intent(context, TopFollowersBrowseActivity::class.java)
                    .putExtra(TopFollowersBrowseActivity.EXTRA_USERNAME, username)).use { scenario ->
                    settle()
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        val toolbar = activity.findViewById<ToolbarLayout>(R.id.top_followers_browse_root).toolbar
                        assertFalse((0 until toolbar.menu.size()).any {
                            toolbar.menu.getItem(it).title == activity.getString(R.string.top_followers_browser_search)
                        })
                        assertFalse("Search must not autofocus on opening", search.hasFocus())
                        assertEquals("Voice input is replaced by search refinements", View.GONE, search.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).visibility)
                        compactWidth = search.width
                        (search as com.tjg.twidget.ui.Sesl95SearchView).expand(showKeyboard = false, animate = false)
                        assertEquals("First row must meet the rounded list edge", 0,
                            activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).paddingTop)
                        assertTrue("Idle search should be compact", search.width < activity.window.decorView.width)
                        assertEquals(View.VISIBLE, search.findViewById<View>(androidx.appcompat.R.id.search_more_btn).visibility)
                        val bounds = Rect()
                        assertTrue(search.getGlobalVisibleRect(bounds))
                        val safe = ViewCompat.getRootWindowInsets(search)!!.getInsets(WindowInsetsCompat.Type.navigationBars())
                        assertTrue(bounds.bottom <= activity.window.decorView.height - safe.bottom)
                        assertNotNull("Floating search must have its native surface", search.findViewById<View>(androidx.appcompat.R.id.search_plate).background)
                    }
                    capture("followers-search-idle-$mode")
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                        assertTrue("Use the native SESL fast scroller", list.seslIsFastScrollerEnabled())
                        val indexer = list.adapter as android.widget.SectionIndexer
                        assertTrue("Only visible follower rows may be attached", list.childCount < list.adapter!!.itemCount)
                        assertEquals(listOf("1", "5", "10", "15", "20", "25", "30"), indexer.sections.toList())
                        activity.findViewById<ToolbarLayout>(R.id.top_followers_browse_root).setExpanded(false, false)
                        list.scrollToPosition(2)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                        list.scrollBy(0, 20)
                        val scroller = RecyclerView::class.java.getDeclaredField("mFastScroller")
                            .apply { isAccessible = true }.get(list)
                        val preview = scroller.javaClass.getDeclaredField("mPrimaryText")
                            .apply { isAccessible = true }.get(scroller) as android.widget.TextView
                        if (AppAppearance.font(context) != AppAppearance.Font.SYSTEM) {
                            assertNotEquals(android.graphics.Typeface.create("sans-serif", 0), preview.typeface)
                        }
                        val track = scroller.javaClass.getDeclaredField("mTrackImage")
                            .apply { isAccessible = true }.get(scroller) as View
                        val listPosition = IntArray(2).also(list::getLocationInWindow)
                        val toolbar = activity.findViewById<ToolbarLayout>(R.id.top_followers_browse_root).toolbar
                        val toolbarPosition = IntArray(2).also(toolbar::getLocationInWindow)
                        val searchPosition = IntArray(2).also(activity.findViewById<View>(R.id.top_followers_browse_search)::getLocationInWindow)
                        assertTrue("Native track must start below toolbar", listPosition[1] + track.top >= toolbarPosition[1] + toolbar.height)
                        val thumb = scroller.javaClass.getDeclaredField("mThumbImage")
                            .apply { isAccessible = true }.get(scroller) as View
                        val edge = listPosition[1] + track.top - thumb.height / 2
                        assertTrue("Toolbar inset must not be counted twice", edge <= maxOf(listPosition[1], toolbarPosition[1] + toolbar.height) + (35 * activity.resources.displayMetrics.density).toInt())
                        assertTrue("Search must overlay the full-height list", listPosition[1] + list.height > searchPosition[1])


                        assertTrue("Native track must end above search", listPosition[1] + track.bottom <= searchPosition[1])
                        val decor = activity.window.decorView
                        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                        decor.draw(android.graphics.Canvas(bitmap))
                        File(context.filesDir, "followers-fast-scroller-$mode.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        search.setQuery("follower30", false)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                        assertShortSurface(activity)
                    }
                    scenario.recreate()
                    settle()
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        assertEquals("follower30", search.query.toString())
                        assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                        assertShortSurface(activity)
                        search.findViewById<View>(androidx.appcompat.R.id.search_close_btn).performClick()
                        search.clearFocus()
                    }
                    settle()
                    scenario.onActivity { activity ->
                        activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).scrollToPosition(29)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<RecyclerView>(R.id.top_followers_browse_list)
                        val last = list.findViewHolderForAdapterPosition(29)!!.itemView
                        val rowBounds = Rect().also(last::getGlobalVisibleRect)
                        val searchBounds = Rect().also(activity.findViewById<View>(R.id.top_followers_browse_search)::getGlobalVisibleRect)
                        assertTrue("Last follower must scroll above search", rowBounds.bottom <= searchBounds.top)
                    }
                    capture("followers-search-$mode")
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        assertEquals(30, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                        search.requestFocus()
                        ViewCompat.getWindowInsetsController(search)!!.show(WindowInsetsCompat.Type.ime())
                    }
                    settle()
                    capture("followers-search-keyboard-$mode")
                    capture("followers-search-before-keyboard-assert-$mode")
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        val insets = ViewCompat.getRootWindowInsets(search)!!
                        assertEquals("Filter menu stays available while typing", View.VISIBLE, search.findViewById<View>(androidx.appcompat.R.id.search_more_btn).visibility)
                        assertTrue("Focused search should expand", search.width > compactWidth)
                        assertTrue("Keyboard must be shown", insets.isVisible(WindowInsetsCompat.Type.ime()))
                        val bounds = Rect()
                        search.getGlobalVisibleRect(bounds)
                        assertTrue("Search must sit above keyboard", bounds.bottom <= activity.window.decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                        search.setQuery("no such follower", false)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        ViewCompat.getWindowInsetsController(search)!!.hide(WindowInsetsCompat.Type.ime())
                    }
                    settle()
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        assertFalse("Keyboard dismissal collapses search", (search as com.tjg.twidget.ui.Sesl95SearchView).isFloatingExpanded)
                        assertFalse(search.hasFocus())
                        assertEquals(compactWidth, search.width)
                        assertEquals("", search.query.toString())
                        search.expand(showKeyboard = false, animate = false)
                    }
                    scenario.onActivity { activity ->
                        val search = activity.findViewById<SearchView>(R.id.top_followers_browse_search)
                        assertEquals(View.GONE, search.findViewById<View>(androidx.appcompat.R.id.search_voice_btn).visibility)
                        search.setQuery("follower30", false)
                    }
                    settle()
                    scenario.onActivity { activity ->
                        assertEquals(1, activity.findViewById<RecyclerView>(R.id.top_followers_browse_list).adapter!!.itemCount)
                    }
                }
            }
        } finally {
            TopFollowersArchiveStore.clear(context, username)
            instrumentation.runOnMainSync { AppAppearance.setMode(context, originalMode) }
        }
    }
}
