package com.tjg.twidget.main

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.DashboardRecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class DashboardRecyclerInteractionInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var activeGrid: DashboardRecyclerView? = null
    private val fixture = listOf("avg_views", "post_rate", "follower_ratio", "daily_streak", "followers", "top_followers")

    @Suppress("DEPRECATION")
    private fun withDashboard(columns: Int, rtl: Boolean = false, editing: Boolean = true, block: (ActivityScenario<MainActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        fun owned(key: String) = key.startsWith("dashboard_") || key == "refresh_on_launch" || key == "username"
        val saved = prefs.all.filterKeys(::owned)
        var resources: Resources? = null
        var original: Configuration? = null
        var metrics: DisplayMetrics? = null
        try {
            prefs.edit().putBoolean("refresh_on_launch", false).apply {
                if (TwidgetStore.accounts(context).isEmpty()) putString("username", "dashboard_fixture")
            }.commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    resources = activity.resources
                    original = Configuration(activity.resources.configuration)
                    metrics = DisplayMetrics().apply { setTo(activity.resources.displayMetrics) }
                    val config = Configuration(original).apply {
                        screenWidthDp = if (columns == 4) 800 else 400
                        densityDpi = (metrics!!.widthPixels * 160f / screenWidthDp).roundToInt()
                        screenHeightDp = (metrics!!.heightPixels * 160f / densityDpi).roundToInt()
                        smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
                    }
                    activity.resources.updateConfiguration(config, metrics)
                    TwidgetStore.saveDashboardCards(activity, fixture, activity.selectedAccount)
                    fixture.forEach { TwidgetStore.setDashboardCardExpanded(activity, it, false, activity.selectedAccount) }
                    activity.render(refreshRemote = false)
                    grid(activity).layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    activity.editModeController.setEditMode(editing)
                    activeGrid = grid(activity)
                }
                settle()
                scenario.onActivity { assertEquals(columns, grid(it).columnCount) }
                block(scenario)
            }
        } finally {
            activeGrid = null
            instrumentation.runOnMainSync { resources?.updateConfiguration(original, metrics) }
            prefs.edit().apply {
                prefs.all.keys.filter(::owned).forEach(::remove)
                saved.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
    }
    private fun settle(ms: Long = 450) {
        SystemClock.sleep(ms)
        instrumentation.waitForIdleSync()
        // Completion follows the native animator, including the device's
        // animation scale, rather than assuming a fixed number of frames.
        val deadline = SystemClock.uptimeMillis() + 3000
        do {
            var running = false
            instrumentation.runOnMainSync { running = activeGrid?.itemAnimator?.isRunning == true }
            if (!running) break
            SystemClock.sleep(32)
            instrumentation.waitForIdleSync()
        } while (SystemClock.uptimeMillis() < deadline)
    }
    private fun grid(activity: MainActivity) = activity.findViewById<DashboardRecyclerView>(R.id.dashboard_content)
    private fun tile(activity: MainActivity, id: String): ViewGroup = grid(activity).findViewWithTag(id)
    private class Pointer(private val grid: DashboardRecyclerView, val x: Float, val y: Float) {
        private val down = SystemClock.uptimeMillis()
        fun send(action: Int, dx: Float = 0f, dy: Float = 0f) {
            val origin = IntArray(2).also(grid::getLocationOnScreen)
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, origin[0] + x + dx, origin[1] + y + dy, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
            grid.dispatchTouchEvent(event)
            event.recycle()
        }
    }
    private fun assertSettledGrid(activity: MainActivity) {
        val view = grid(activity)
        val tiles = (0 until view.childCount).map { view.getChildAt(it) }.filter { it.tag in fixture }
        for (a in tiles) {
            val card = a as ViewGroup
            val content = (0 until card.childCount).first { card.getChildAt(it).getTag(R.id.dashboard_card_content) == true }
            for (index in 0 until card.childCount) if (card.getChildAt(index) is com.tjg.twidget.ui.CardShadowView)
                assertTrue("Edit shadows stay behind content after resize and Undo", index < content)
            assertEquals(1f, a.alpha, .001f)
            assertEquals(0f, a.translationX, 1f)
            assertEquals(0f, a.translationY, 1f)
            tiles.filter { it !== a }.forEach { b ->
                assertFalse("Native slots remain separate while a resize is held",
                    Rect.intersects(Rect(a.left, a.top, a.right, a.bottom), Rect(b.left, b.top, b.right, b.bottom)))
            }
        }
    }

    private fun pointer(activity: MainActivity, id: String): Pointer {
        val card = tile(activity, id)
        return Pointer(grid(activity), card.left + card.width / 2f, card.top + card.height / 2f)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun menu(): ViewGroup? = android.view.inspector.WindowInspector.getGlobalWindowViews()
        .flatMap(::descendants).filterIsInstance<ViewGroup>().firstOrNull {
            it.id == R.id.popup_item_layout && it.isShown
        }
    private fun choose(activity: MainActivity, action: Int) {
        val root = requireNotNull(menu()) { "A held card opens native actions" }
        val label = descendants(root).filterIsInstance<android.widget.TextView>()
            .firstOrNull { it.id == R.id.popup_option_text && it.text == activity.getString(action) }
        assertNotNull("Requested card action is available", label)
        (label!!.parent as View).performClick()
    }

    @Test fun explicitRefreshRetriesAllSourcesWithoutReplacingSavedStats() = withDashboard(2, editing = false) { scenario ->
        val calls = java.util.concurrent.CopyOnWriteArrayList<Triple<String, DashboardDataSource, Boolean>>()
        lateinit var controller: MainSyncController
        lateinit var before: com.tjg.twidget.data.ProfileStats
        scenario.onActivity { activity ->
            before = TwidgetStore.currentStats(activity, activity.selectedAccount)
            controller = MainSyncController(activity) { account, source, force ->
                calls += Triple(account, source, force)
                if (source == DashboardDataSource.POSTS) throw java.io.IOException("fixture offline")
                source != DashboardDataSource.TOP_FOLLOWERS
            }
            activity.syncController = controller
            controller.sync(userInitiated = true)
            controller.sync(userInitiated = true)
        }
        settle(800)
        scenario.onActivity { activity ->
            val selectedCalls = calls.filter { it.first.equals(activity.selectedAccount, true) }
            assertEquals(DashboardDataSource.entries.toSet(), selectedCalls.map { it.second }.toSet())
            assertEquals(4, selectedCalls.size)
            assertTrue(selectedCalls.all { it.third })
            assertTrue(calls.filterNot { it.first.equals(activity.selectedAccount, true) }.none { it.third })
            assertFalse(controller.isSyncing)
            assertEquals(before, TwidgetStore.currentStats(activity, activity.selectedAccount))
        }
    }


    @Test fun appLaunchRefreshesEveryProfileWithTheVisibleProfileFirst() = withDashboard(2, editing = false) { scenario ->
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, DashboardDataSource>>()
        lateinit var expected: List<String>
        lateinit var selected: String
        scenario.onActivity { activity ->
            expected = TwidgetStore.accounts(activity)
            selected = activity.selectedAccount
            activity.syncController = MainSyncController(activity) { account, source, _ ->
                calls += account to source
                true
            }
            activity.syncController.sync(showProgress = false)
        }
        settle(1000)
        val profiles = calls.filter { it.second == DashboardDataSource.PROFILE }.map { it.first.lowercase() }
        assertEquals(selected.lowercase(), profiles.first())
        assertEquals(expected.map { it.lowercase() }.toSet(), profiles.toSet())
        assertEquals(profiles.toSet().size, profiles.size)
    }

    @Test fun pullToRefreshReachesListenerAtTopButNotWhileScrolledOrEditing() = withDashboard(2, editing = false) { scenario ->
        var refreshes = 0
        lateinit var refresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        fun pull() {
            val down = SystemClock.uptimeMillis()
            scenario.onActivity { activity ->
                refresh = activity.findViewById(R.id.main_refresh)
                val x = refresh.width / 2f
                val start = activity.dp(80).toFloat()
                fun event(action: Int, y: Float, time: Long) {
                    val e = MotionEvent.obtain(down, time, action, x, y, 0)
                    refresh.dispatchTouchEvent(e)
                    e.recycle()
                }
                event(MotionEvent.ACTION_DOWN, start, down)
                repeat(20) { event(MotionEvent.ACTION_MOVE, start + activity.dp(16) * (it + 1), down + (it + 1) * 16L) }
                event(MotionEvent.ACTION_UP, start + activity.dp(320), down + 350)
            }
            settle(600)
        }
        scenario.onActivity { activity ->
            activity.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.main_toolbar_layout).setExpanded(false, false)
            activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.main_refresh)
                .setOnRefreshListener { refreshes++ }
            grid(activity).scrollToPosition(0)
        }
        settle()
        scenario.onActivity { activity ->
            assertTrue("Collapsed header blocks refresh", activity.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.main_refresh).canChildScrollUp())
        }
        pull()
        assertEquals("First pull expands the header", 0, refreshes)
        scenario.onActivity { activity ->
            assertEquals("Header expands before refresh", 0,
                activity.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.main_toolbar_layout).appBarLayout.top)
        }
        pull()
        assertEquals("Pull at the expanded top refreshes", 1, refreshes)
        scenario.onActivity { activity ->
            refresh.isRefreshing = false
            grid(activity).scrollBy(0, activity.dp(220))
        }
        settle()
        pull()
        assertEquals("Scrolling back does not refresh mid-list", 1, refreshes)
        scenario.onActivity { activity ->
            grid(activity).scrollToPosition(0)
            activity.editModeController.setEditMode(true)
        }
        settle()
        pull()
        assertEquals("Editing does not trigger refresh", 1, refreshes)
    }

    @Test fun modeTransitionsEaseWithoutSnappingAndReverseFromTheCurrentPose() = withDashboard(2, editing = false) { scenario ->
        scenario.onActivity { activity ->
            val card = tile(activity, "post_rate")
            assertEquals(1f, card.scaleX, .001f)
            activity.editModeController.setEditMode(true)
            assertEquals("Entry starts at the normal scale", 1f, card.scaleX, .001f)
        }
        settle(450)
        scenario.onActivity { activity ->
            val card = tile(activity, "post_rate")
            assertEquals(.97f, card.scaleX, .001f)
            assertTrue("Edit mode adds no border overlay", (0 until card.childCount).none {
                card.getChildAt(it).tag == "dashboard_edit_border"
            })
            activity.editModeController.setEditMode(false)
            assertEquals("Exit starts from the edit scale", .97f, card.scaleX, .001f)
        }
        SystemClock.sleep(80)
        scenario.onActivity { activity ->
            val card = tile(activity, "post_rate")
            val scale = card.scaleX
            activity.editModeController.setEditMode(true)
            assertEquals("Reversal preserves the current scale", scale, card.scaleX, .001f)
        }
        settle(450)
        scenario.onActivity { activity -> activity.editModeController.setEditMode(false) }
        settle(450)
        scenario.onActivity { activity ->
            val card = tile(activity, "post_rate")
            assertEquals(1f, card.scaleX, .001f)
            assertEquals(0f, card.rotation, .001f)
            assertNull(card.getTag(R.id.dashboard_jiggle))
        }
    }

    @Test fun editJiggleStaysSubtleAndStopsForPickupAndExit() = withDashboard(2) { scenario ->
        val samples = mutableListOf<Float>()
        repeat(8) {
            scenario.onActivity { activity ->
                val card = tile(activity, "post_rate")
                samples += card.rotation
                assertTrue("Jiggle remains subtle", kotlin.math.abs(card.rotation) <= .51f)
            }
            SystemClock.sleep(60)
        }
        assertTrue("Edit cards animate", samples.max() - samples.min() > .1f)
        scenario.onActivity { activity ->
            val card = tile(activity, "post_rate")
            DashboardCardMotion.lift(card)
            assertEquals(0f, card.rotation, 0f)
            assertNull(card.getTag(R.id.dashboard_jiggle))
            DashboardCardMotion.release(card)
            assertNotNull(card.getTag(R.id.dashboard_jiggle))
            activity.editModeController.setEditMode(false)
        }
        settle()
        scenario.onActivity { activity ->
            val card = tile(activity, "post_rate")
            assertEquals(0f, card.rotation, 0f)
            assertNull(card.getTag(R.id.dashboard_jiggle))
        }
    }

    @Test fun heldCardStaysInsideTheVisibleViewportAtTheToolbar() = withDashboard(2) { scenario ->
        scenario.onActivity { activity -> (grid(activity).layoutManager as GridLayoutManager).scrollToPositionWithOffset(0, 0) }
        settle()
        lateinit var card: ViewGroup
        lateinit var pointer: Pointer
        scenario.onActivity { activity ->
            card = tile(activity, "post_rate")
            pointer = Pointer(grid(activity), card.left + card.width / 2f, card.top + card.height / 2f)
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        settle(650)
        repeat(12) { step ->
            scenario.onActivity { activity -> pointer.send(MotionEvent.ACTION_MOVE, 0f, -activity.dp(400) * (step + 1) / 12f) }
            settle(20)
        }
        scenario.onActivity { activity ->
            val visible = Rect()
            assertTrue(grid(activity).getLocalVisibleRect(visible))
            assertTrue("Held card clears toolbar clipping", card.y >= visible.top)
            assertTrue("Held card remains fully visible", card.y + card.height <= visible.bottom)
            pointer.send(MotionEvent.ACTION_CANCEL)
        }
        settle(400)
    }

    @Test fun generalStatCardsIncludeStoredHistoryWithoutChangingWeeklyAnalytics() = withDashboard(2, editing = false) { scenario ->
        scenario.onActivity { activity ->
            val original = activity.analytics
            val imports = activity.importedAnalytics
            try {
                val now = System.currentTimeMillis()
                fun post(id: String, views: Long, age: Long) = com.tjg.twidget.analytics.PostSummary("https://x.com/example/status/$id", "Stored post", views,1,0,0,0,1,now-age,"","Example","example","",observedAt=now)
                val recent = post("1", 100, 86_400_000L)
                val old = post("2", 900, 30 * 86_400_000L)
                val weekly = com.tjg.twidget.analytics.AnalyticsClient.analyticsFromPosts("example",100,1,false,listOf(recent),cachedAt=now)
                    .copy(historicalPosts=listOf(old))
                activity.analytics = weekly
                activity.importedAnalytics = emptyList()
                val card = activity.dashboardBinder.createCardPreview(DashboardCardType.AVG_VIEWS, span = 1)
                assertEquals("500", card.findViewById<android.widget.TextView>(R.id.followers_value).text.toString())
                assertTrue(card.findViewById<android.widget.TextView>(R.id.stat_detail).text.toString().contains("stored"))
                assertEquals(100L, activity.analytics!!.totalViews)
                assertEquals(1, activity.analytics!!.postsAnalyzed)
            } finally {
                activity.analytics = original
                activity.importedAnalytics = imports
            }
        }
    }

    @Test fun sharedArchiveFeedsDashboardBriefAndBookmarkCounters() = withDashboard(2, editing = false) { scenario ->
        val context = instrumentation.targetContext
        val account = "history_fixture"
        try {
            val now = System.currentTimeMillis()
            fun post(id: String, views: Long, age: Long) = com.tjg.twidget.analytics.PostSummary(
                "https://x.com/$account/status/$id", "Stored post", views, 12, 0, 0, 0, 12, now-age, "", "Fixture", account, "",
                observedAt = now, bookmarks = 4)
            val root = org.json.JSONObject().put("userName", account).put("userId", "987654321")
                .put("updatedAt", now).put("postCount", 2).put("replyCount", 0)
                .put("posts", org.json.JSONArray().put(com.tjg.twidget.analytics.AnalyticsClient.serializePost(post("9876543201", 100, 86_400_000L)))
                    .put(com.tjg.twidget.analytics.AnalyticsClient.serializePost(post("9876543202", 900, 25 * 86_400_000L))))
                .put("backfill", org.json.JSONObject().put("status", "partial"))
            com.tjg.twidget.analytics.AnalyticsClient.cacheArchive(context, account, root, "987654321")
            val cached = com.tjg.twidget.analytics.AnalyticsClient.cached(context, account)!!
            assertEquals(1, cached.postsAnalyzed)
            assertEquals(100L, cached.totalViews)
            assertEquals(2, cached.historicalPosts.size)
            assertEquals(2, com.tjg.twidget.brief.BriefPostCoach.reviewablePosts(cached, account, System.currentTimeMillis()).size)
            assertTrue(com.tjg.twidget.analytics.InteractionStore.all(context, account).posts.all { it.counts.bookmarks == 4L })
            scenario.onActivity { activity ->
                val original = activity.analytics
                val imports = activity.importedAnalytics
                try {
                    activity.analytics = cached
                    activity.importedAnalytics = emptyList()
                    val card = activity.dashboardBinder.createCardPreview(DashboardCardType.AVG_VIEWS, span = 1)
                    assertEquals("500", card.findViewById<android.widget.TextView>(R.id.followers_value).text.toString())
                    assertTrue(card.findViewById<android.widget.TextView>(R.id.stat_detail).text.toString().contains("stored"))
                } finally { activity.analytics = original; activity.importedAnalytics = imports }
            }
        } finally {
            listOf("twidget_analytics", "twidget_interactions", "twidget_daily_streak").forEach { name ->
                val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                preferences.edit().apply { preferences.all.keys.filter { it.contains(account) }.forEach(::remove) }.commit()
            }
        }
    }

    @Test fun graphCardsUseEqualInsetsAndFillTheSameVerticalSpaceAcrossWidths() = withDashboard(2, editing = false) { scenario ->
        scenario.onActivity { activity ->
            fun descendantsOf(view: View): List<View> = listOf(view) +
                if (view is ViewGroup) (0 until view.childCount).flatMap { descendantsOf(view.getChildAt(it)) } else emptyList()
            for (widthDp in listOf(320, 400, 760)) for (card in listOf(DashboardCardType.FOLLOWERS, DashboardCardType.POST_RATE)) {
                val root = activity.dashboardBinder.createCardPreview(card, span = 2)
                val width = activity.dp(widthDp)
                val height = activity.dp(160)
                com.tjg.twidget.ui.DashboardCardDesign.apply(root, height, width)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                val graph = descendantsOf(root).filterIsInstance<com.tjg.twidget.ui.MetricChartView>().single()
                val inset = activity.dp(10)
                assertEquals("${card.id} top inset at $widthDp", inset, root.paddingTop)
                assertEquals("${card.id} bottom inset at $widthDp", inset, root.paddingBottom)
                assertEquals("${card.id} end inset at $widthDp", inset, root.paddingRight)
                val panel = graph.parent as View
                assertEquals("Graph uses the whole panel height", panel.height, graph.height)
                assertEquals("Graph reaches the common top edge", inset, panel.top + graph.top)
                assertEquals("Graph reaches the common bottom edge", height - inset, panel.top + graph.bottom)
            }
        }
    }

    @Test fun expandedStreakUsesSevenDayDotsInsteadOfAGraph() = withDashboard(2) { scenario ->
        scenario.onActivity { activity ->
            TwidgetStore.setDashboardCardExpanded(activity, "daily_streak", true, activity.selectedAccount)
            activity.render(refreshRemote = false)
        }
        settle()
        scenario.onActivity { activity ->
            val card = tile(activity, "daily_streak")
            val children = descendants(card)
            assertTrue(children.none { it is com.tjg.twidget.ui.MetricChartView })
            val labels = listOf(R.string.streak_day_active, R.string.streak_day_inactive, R.string.streak_day_unknown).map(activity::getString)
            assertEquals(7, children.count { view -> labels.any { view.contentDescription?.toString()?.endsWith(": $it") == true } })
        }
    }

    @Test fun realViewsStayOpaqueDuringNativeDragAndCancellationRestoresOrder() {
        for (columns in listOf(2, 4)) withDashboard(columns) { scenario ->
            lateinit var source: ViewGroup
            lateinit var pointer: Pointer
            var distance = 0f
            scenario.onActivity { activity ->
                source = tile(activity, "post_rate")
                val view = grid(activity)
                assertTrue(view.layoutManager is GridLayoutManager)
                pointer = Pointer(view, source.left + source.width / 2f, source.top + source.height / 2f)
                distance = (tile(activity, "avg_views").left - source.left) * 1.15f
                pointer.send(MotionEvent.ACTION_DOWN)
            }
            settle(650)
            scenario.onActivity { activity ->
                assertNull("Holding opens actions before movement begins", activity.editModeController.draggedCardId)
                assertNotNull(menu())
                assertSame("The live card stays in RecyclerView", source, tile(activity, "post_rate"))
                assertSame(grid(activity), source.parent)
            }
            repeat(12) { step ->
                scenario.onActivity { pointer.send(MotionEvent.ACTION_MOVE, distance * (step + 1) / 12) }
                settle(20)
            }
            scenario.onActivity { activity ->
                assertSame(source, tile(activity, "post_rate"))
                assertEquals("Selected card stays opaque", 1f, source.alpha, .001f)
                val viewport = grid(activity)
                assertTrue("Held card stays inside the left edge", source.x >= viewport.paddingLeft - 1f)
                assertTrue("Held card stays inside the right edge",
                    source.x + source.width <= viewport.width - viewport.paddingRight + 1f)
                assertEquals("Order is provisional until drop", fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
                assertNotEquals(fixture, activity.editModeController.dragPreviewOrder)
                pointer.send(MotionEvent.ACTION_CANCEL, distance)
            }
            settle(750)
            scenario.onActivity { activity ->
                assertNull(activity.editModeController.draggedCardId)
                assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
                assertEquals(fixture, activity.dashboardBinder.cardIds())
                assertSettledGrid(activity)
            }
        }
    }


    @Test fun editModeAtTheTopKeepsTheFirstCardInPlace() = withDashboard(2, editing = false) { scenario ->
        var top = 0
        var hidden = false
        fun screenTop(view: View): Int {
            val parent = view.parent as View
            return IntArray(2).also(parent::getLocationInWindow)[1] + view.top
        }
        scenario.onActivity { activity ->
            grid(activity).stopScroll()
            (grid(activity).layoutManager as GridLayoutManager).scrollToPositionWithOffset(0,0)
            activity.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.main_toolbar_layout).apply {
                setExpanded(false, false)
                appBarLayout.seslSetHide(false,false)
            }
        }
        settle()
        scenario.onActivity { activity -> grid(activity).scrollBy(0, -activity.dp(10000)) }
        settle()
        scenario.onActivity { activity ->
            val manager = grid(activity).layoutManager as GridLayoutManager
            assertTrue("First dashboard row is visible",manager.findFirstVisibleItemPosition() <= 1)
            assertTrue("First row is at the content boundary",manager.getDecoratedTop(tile(activity,"avg_views")) >= grid(activity).paddingTop - 1)
            top = screenTop(tile(activity,"avg_views"))
            hidden = activity.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.main_toolbar_layout).appBarLayout.seslIsHided()
            activity.editModeController.setEditMode(true)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals("Entering edit mode keeps the first card's window position",top,screenTop(tile(activity,"avg_views")))
            assertEquals("Entering edit mode retains app-bar geometry",hidden,activity.findViewById<dev.oneuiproject.oneui.layout.ToolbarLayout>(R.id.main_toolbar_layout).appBarLayout.seslIsHided())
            activity.editModeController.setEditMode(false)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals("Leaving edit mode keeps the first card's window position",top,screenTop(tile(activity,"avg_views")))
            val manager = grid(activity).layoutManager as GridLayoutManager
            assertTrue("Mode changes retain the first row",manager.findFirstVisibleItemPosition() <= 1)
        }
    }

    @Test fun movingBetweenVisibleRowsKeepsTheViewportStill() = withDashboard(2) { scenario ->
        lateinit var pointer: Pointer
        var distance = 0f
        var offset = 0
        scenario.onActivity { activity ->
            val view = grid(activity)
            val source = tile(activity, "avg_views")
            val target = tile(activity, "follower_ratio")
            pointer = Pointer(view, source.left + source.width/2f, source.top + source.height/2f)
            distance = target.top - source.top.toFloat()
            offset = view.computeVerticalScrollOffset()
            assertTrue("Destination is away from the scroll edge", pointer.y + distance < view.height - activity.dp(64))
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        settle(650)
        repeat(12) { step ->
            scenario.onActivity { pointer.send(MotionEvent.ACTION_MOVE, dy = distance*(step+1)/12) }
            settle(30)
            scenario.onActivity { activity ->
                assertEquals("Reordering must not scroll to its drop target", offset, grid(activity).computeVerticalScrollOffset())
            }
        }
        scenario.onActivity { activity ->
            assertNotEquals("The stationary viewport still permits reordering", fixture, activity.editModeController.dragPreviewOrder)
            pointer.send(MotionEvent.ACTION_UP, dy = distance)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals("Dropping must not move the viewport", offset, grid(activity).computeVerticalScrollOffset())
        }
    }

    @Test fun nativeDragCommitsOnDropAndUndoRestoresTheOriginalOrder() = withDashboard(4) { scenario ->
        lateinit var pointer: Pointer
        var distance = 0f
        scenario.onActivity { activity ->
            val source = tile(activity, "post_rate")
            pointer = Pointer(grid(activity), source.left + source.width / 2f, source.top + source.height / 2f)
            distance = (tile(activity, "avg_views").left - source.left) * 1.15f
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        settle(650)
        repeat(12) { step ->
            scenario.onActivity { pointer.send(MotionEvent.ACTION_MOVE, distance * (step + 1) / 12) }
            settle(20)
        }
        scenario.onActivity { pointer.send(MotionEvent.ACTION_UP, distance) }
        settle(750)
        scenario.onActivity { activity ->
            assertNull(activity.editModeController.draggedCardId)
            assertEquals(listOf("post_rate", "avg_views") + fixture.drop(2), TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            assertSettledGrid(activity)
            activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            assertSettledGrid(activity)
        }
    }


    @Test fun nativeRowsAlignWithTopFollowersAndUndoRestoresProfileScopedMoves() = withDashboard(4) { scenario ->
        val other = "native-dashboard-other-profile"
        scenario.onActivity { activity ->
            val view = grid(activity)
            assertEquals("Top Followers keeps its roomier row", activity.dp(176), tile(activity, "top_followers").height)
            assertEquals("Its neighbour fills the same native row", tile(activity, "top_followers").height, tile(activity, "followers").height)
            TwidgetStore.saveDashboardCards(activity, listOf("daily_streak", "avg_views"), other)
            assertTrue(activity.editModeController.moveCard(DashboardCardType.POST_RATE, -1))
        }
        settle()
        scenario.onActivity { activity ->
            assertNotEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            val undo = activity.findViewById<View>(com.google.android.material.R.id.snackbar_action)
            assertNotNull("Native SESL Undo is offered", undo)
            undo.performClick()
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            assertEquals(listOf("daily_streak", "avg_views"), TwidgetStore.dashboardCards(activity, other))
            assertSettledGrid(activity)
        }
    }
    @Test fun removeButtonTakesPriorityOverTheResizeGutter() = withDashboard(4) { scenario ->
        scenario.onActivity { activity ->
            val view = grid(activity)
            val card = tile(activity, "avg_views")
            val button = (0 until card.childCount).map(card::getChildAt).filterIsInstance<android.widget.ImageButton>().first()
            val point = IntArray(2).also(button::getLocationOnScreen)
            val origin = IntArray(2).also(view::getLocationOnScreen)
            val pointer = Pointer(view, point[0] - origin[0] + button.width / 2f, point[1] - origin[1] + button.height / 2f)
            pointer.send(MotionEvent.ACTION_DOWN)
            pointer.send(MotionEvent.ACTION_UP)
        }
        settle()
        scenario.onActivity { activity ->
            assertFalse("Removal remains directly tappable", "avg_views" in TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            assertFalse(activity.dashboardBinder.isResizing)
        }
    }


    @Test fun followerCountKeepsWidgetLayoutThroughNativeResizeAndUndo() {
        for (columns in listOf(2, 4)) withDashboard(columns) { scenario ->
            val order = listOf("follower_count", "daily_streak", "avg_views", "post_rate")
            scenario.onActivity { activity ->
                TwidgetStore.saveDashboardCards(activity, order, activity.selectedAccount)
                TwidgetStore.setDashboardCardExpanded(activity, "follower_count", false, activity.selectedAccount)
                activity.render(refreshRemote = false)
            }
            settle()
            fun assertCount(activity: MainActivity, span: Int) {
                val card = tile(activity, "follower_count")
                assertEquals(span, card.getTag(R.id.dashboard_card_span))
                val content = card.findViewById<com.tjg.twidget.ui.FollowerCountCardView>(R.id.followers_value)
                val layout = requireNotNull(content.fittedLayout())
                assertEquals(content.contentDescription.toString(), layout.lines.flatten().joinToString(" ") { it.text })
                assertTrue(layout.height <= content.height - content.paddingTop - content.paddingBottom)
                assertTrue(layout.bounds.all { it.width() <= content.width - content.paddingLeft - content.paddingRight })
                assertNull("The widget's account footer is omitted", content.findViewById<View>(R.id.dashboard_count_footer))
            }
            scenario.onActivity { activity ->
                assertCount(activity, 1)
                activity.editModeController.resizeDashboardCard(DashboardCardType.FOLLOWER_COUNT)
            }
            settle()
            scenario.onActivity { activity ->
                assertCount(activity, 2)
                activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            settle()
            scenario.onActivity { activity -> assertCount(activity, 1) }
            scenario.recreate()
            settle()
            scenario.onActivity { activity -> assertCount(activity, 1) }
        }
    }

    @Test fun resizeUndoKeepsRebuiltCardsAboveTheirEditShadows() {
        for (columns in listOf(2, 4)) withDashboard(columns) { scenario ->
            repeat(3) {
                scenario.onActivity { activity -> activity.editModeController.resizeDashboardCard(DashboardCardType.AVG_VIEWS) }
                settle()
                scenario.onActivity { activity ->
                    assertSettledGrid(activity)
                    activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
                }
                settle()
                scenario.onActivity { activity ->
                    assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
                    assertFalse(TwidgetStore.isDashboardCardExpanded(activity, "avg_views", activity.selectedAccount))
                    assertSettledGrid(activity)
                }
            }
        }
    }

    @Test fun holdMenuEnlargesAndShrinksInTwoAndFourColumnsIncludingRtl() {
        for (columns in listOf(2, 4)) for (rtl in listOf(false, true)) withDashboard(columns, rtl) { scenario ->
            val id = if (columns == 2) "post_rate" else "daily_streak"
            lateinit var pointer: Pointer
            var outerEdge = 0
            scenario.onActivity { activity ->
                val card = tile(activity, id)
                outerEdge = if (rtl) card.left else card.right
                assertFalse("Resize grips no longer compete with picking up the card",
                    descendants(card).any { (it.tag as? String)?.startsWith("dashboard_resize_") == true })
                pointer = pointer(activity, id)
                pointer.send(MotionEvent.ACTION_DOWN)
            }
            settle(650)
            scenario.onActivity { activity ->
                assertNull(activity.editModeController.draggedCardId)
                assertNotNull(menu())
                pointer.send(MotionEvent.ACTION_UP)
                choose(activity, R.string.dashboard_size_enlarge)
            }
            settle()
            scenario.onActivity { activity ->
                val card = tile(activity, id)
                assertEquals(2, card.getTag(R.id.dashboard_card_span))
                assertEquals("The trailing card expands inward", outerEdge, if (rtl) card.left else card.right)
                assertTrue(TwidgetStore.isDashboardCardExpanded(activity, id, activity.selectedAccount))
                assertSettledGrid(activity)
                pointer = pointer(activity, id)
                pointer.send(MotionEvent.ACTION_DOWN)
            }
            settle(650)
            scenario.onActivity { activity ->
                pointer.send(MotionEvent.ACTION_UP)
                choose(activity, R.string.dashboard_size_shrink)
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(1, tile(activity, id).getTag(R.id.dashboard_card_span))
                assertFalse(TwidgetStore.isDashboardCardExpanded(activity, id, activity.selectedAccount))
                activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(2, tile(activity, id).getTag(R.id.dashboard_card_span))
                assertSettledGrid(activity)
            }
        }
    }

    @Test fun holdFromNormalModeOpensActionsWithoutPickingUpTheCard() = withDashboard(4, editing = false) { scenario ->
        lateinit var pointer: Pointer
        scenario.onActivity { activity ->
            assertFalse(activity.editModeController.editMode)
            pointer = pointer(activity, "avg_views")
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        settle(650)
        scenario.onActivity { activity ->
            assertTrue(activity.editModeController.editMode)
            assertNull(activity.editModeController.draggedCardId)
            val actions = requireNotNull(menu())
            assertFalse("The hold menu does not offer Move to", descendants(actions).filterIsInstance<android.widget.TextView>().any {
                it.text == activity.getString(R.string.dashboard_move_to)
            })
            pointer.send(MotionEvent.ACTION_UP)
            choose(activity, R.string.dashboard_size_enlarge)
        }
        settle()
        scenario.onActivity { activity ->
            assertTrue(TwidgetStore.isDashboardCardExpanded(activity, "avg_views", activity.selectedAccount))
            assertSettledGrid(activity)
        }
    }

    @Test fun movementBeforeTheHoldDoesNotOpenActions() = withDashboard(4, editing = false) { scenario ->
        lateinit var pointer: Pointer
        scenario.onActivity { activity ->
            pointer = pointer(activity, "post_rate")
            pointer.send(MotionEvent.ACTION_DOWN)
            pointer.send(MotionEvent.ACTION_MOVE, dy = activity.dp(40).toFloat())
        }
        settle(650)
        scenario.onActivity { activity ->
            pointer.send(MotionEvent.ACTION_UP, dy = activity.dp(40).toFloat())
            assertFalse(activity.editModeController.editMode)
            assertNull(menu())
            assertNull(activity.editModeController.draggedCardId)
        }
    }

    @Test fun changingProfileDismissesTheOldCardsMenu() = withDashboard(4) { scenario ->
        lateinit var pointer: Pointer
        var original = ""
        val other = "native-dashboard-other-profile"
        scenario.onActivity { activity ->
            original = activity.selectedAccount
            TwidgetStore.saveDashboardCards(activity, listOf("avg_views", "daily_streak"), other)
            pointer = pointer(activity, "avg_views")
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        settle(650)
        scenario.onActivity { activity ->
            assertNotNull(menu())
            activity.selectedAccount = other
            activity.dashboardBinder.bindContent(refreshRemote = false)
            pointer.send(MotionEvent.ACTION_CANCEL)
        }
        settle()
        scenario.onActivity { activity ->
            assertNull(menu())
            assertEquals(listOf("avg_views", "daily_streak"), activity.dashboardBinder.cardIds())
            assertFalse(TwidgetStore.isDashboardCardExpanded(activity, "avg_views", original))
            assertEquals(fixture, TwidgetStore.dashboardCards(activity, original))
        }
    }

    @Test fun cancellingAMoveRestoresNestedScrollingAndTheCardsDimensions() = withDashboard(4) { scenario ->
        lateinit var pointer: Pointer
        lateinit var source: ViewGroup
        var height = 0
        var distance = 0f
        scenario.onActivity { activity ->
            source = tile(activity, "post_rate")
            height = source.height
            distance = (tile(activity, "avg_views").left - source.left) * 1.15f
            pointer = pointer(activity, "post_rate")
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        settle(650)
        repeat(12) { step ->
            scenario.onActivity { pointer.send(MotionEvent.ACTION_MOVE, distance * (step + 1) / 12) }
            settle(20)
        }
        scenario.onActivity { activity ->
            assertEquals("post_rate", activity.editModeController.draggedCardId)
            assertEquals(height, source.height)
            assertEquals(height, source.getTag(R.id.dashboard_drag_height))
            assertFalse("The app bar stays fixed while a card is moving", grid(activity).isNestedScrollingEnabled)
            activity.editModeController.cancelInteractions()
        }
        settle(750)
        scenario.onActivity { activity ->
            assertNull(activity.editModeController.draggedCardId)
            assertNull(source.getTag(R.id.dashboard_drag_height))
            assertTrue(grid(activity).isNestedScrollingEnabled)
            assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            assertSettledGrid(activity)
            // Cancelling must leave the next hold usable, with no selected
            // ViewHolder or pointer retained in the native helper.
            pointer = pointer(activity, "post_rate")
            pointer.send(MotionEvent.ACTION_DOWN)
        }
        settle(650)
        scenario.onActivity { activity ->
            assertNotNull(menu())
            pointer.send(MotionEvent.ACTION_UP)
            choose(activity, R.string.dashboard_size_enlarge)
        }
        settle()
        scenario.onActivity { activity ->
            assertTrue(TwidgetStore.isDashboardCardExpanded(activity, "post_rate", activity.selectedAccount))
            assertSettledGrid(activity)
        }
    }

    @Test fun releasingAHeldCardDoesNotRepositionItsMenu() {
        for (columns in listOf(2, 4)) withDashboard(columns) { scenario ->
            lateinit var pointer: Pointer
            lateinit var before: Rect
            scenario.onActivity { activity ->
                pointer = pointer(activity, "post_rate")
                pointer.send(MotionEvent.ACTION_DOWN)
            }
            settle(650)
            scenario.onActivity {
                val list = requireNotNull(menu())
                val position = IntArray(2).also(list::getLocationOnScreen)
                before = Rect(position[0], position[1], position[0] + list.width, position[1] + list.height)
                pointer.send(MotionEvent.ACTION_UP)
            }
            settle()
            scenario.onActivity { activity ->
                val list = requireNotNull(menu())
                val position = IntArray(2).also(list::getLocationOnScreen)
                assertEquals("Press and release keep the same popup bounds", before,
                    Rect(position[0], position[1], position[0] + list.width, position[1] + list.height))
                activity.onBackPressedDispatcher.onBackPressed()
            }
            settle()
            scenario.onActivity { activity ->
                assertNull(menu())
                assertTrue("Back dismisses actions before leaving editing", activity.editModeController.editMode)
            }
        }
    }

    @Test fun systemPointerCanMoveDirectlyFromTheHoldMenuIntoDragging() = withDashboard(4) { scenario ->
        var x = 0f; var y = 0f; var distance = 0f
        scenario.onActivity { activity ->
            val source = tile(activity, "post_rate")
            val location = IntArray(2).also(source::getLocationOnScreen)
            x = location[0] + source.width / 2f; y = location[1] + source.height / 2f
            distance = (tile(activity, "avg_views").left - source.left) * 1.15f
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, dx: Float = 0f) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + dx, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN)
        settle(650)
        scenario.onActivity { assertNotNull("Hold opens native actions before movement", menu()) }
        repeat(16) { step -> send(MotionEvent.ACTION_MOVE, distance * (step + 1) / 16); SystemClock.sleep(24) }
        scenario.onActivity { activity ->
            assertEquals("post_rate", activity.editModeController.draggedCardId)
            assertNull("Dragging dismisses the stationary card menu", menu())
            assertEquals(1f, tile(activity, "post_rate").alpha, .001f)
        }
        send(MotionEvent.ACTION_UP, distance)
        settle(750)
        scenario.onActivity { activity ->
            assertEquals(listOf("post_rate", "avg_views") + fixture.drop(2), TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            assertSettledGrid(activity)
        }
    }
}
