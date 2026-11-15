package com.tjg.twidget.main

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.DashboardGridLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DashboardDirectionalResizeInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fixture = listOf("avg_views", "post_rate", "follower_ratio", "daily_streak", "likes_per_post", "total_views")

    @Suppress("DEPRECATION")
    private fun withDashboard(columns: Int, rtl: Boolean = false, cards: List<String> = fixture,
        wide: Set<String> = emptySet(), block: (ActivityScenario<MainActivity>) -> Unit) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        fun owned(key: String) = key.startsWith("dashboard_") || key == "refresh_on_launch"
        val saved = prefs.all.filterKeys(::owned)
        var resources: Resources? = null
        var original: Configuration? = null
        var originalMetrics: android.util.DisplayMetrics? = null
        try {
            prefs.edit().putBoolean("refresh_on_launch", false).commit()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    resources = activity.resources
                    original = Configuration(activity.resources.configuration)
                    originalMetrics = android.util.DisplayMetrics().apply { setTo(activity.resources.displayMetrics) }
                    val config = Configuration(original).apply {
                        screenWidthDp = if (columns == 4) 800 else 400
                        if (columns == 4) {
                            densityDpi = (originalMetrics!!.widthPixels * 160f / 800f).roundToInt()
                            screenHeightDp = (originalMetrics!!.heightPixels * 160f / densityDpi).roundToInt()
                            smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
                        }
                    }
                    activity.resources.updateConfiguration(config, originalMetrics)
                    TwidgetStore.saveDashboardCards(activity, cards, activity.selectedAccount)
                    cards.forEach { TwidgetStore.setDashboardCardExpanded(activity, it, it in wide, activity.selectedAccount) }
                    activity.render(refreshRemote = false)
                    activity.findViewById<View>(R.id.dashboard_content).layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    activity.editModeController.setEditMode(true)
                }
                settle()
                scenario.onActivity { assertEquals(columns, it.findViewById<GridLayout>(R.id.dashboard_content).columnCount) }
                block(scenario)
            }
        } finally {
            instrumentation.runOnMainSync { if (resources != null && original != null) resources!!.updateConfiguration(original, originalMetrics) }
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
    private fun settle() { SystemClock.sleep(400); instrumentation.waitForIdleSync() }
    private fun tile(activity: MainActivity, id: String) = activity.findViewById<GridLayout>(R.id.dashboard_content).findViewWithTag<ViewGroup>(id)
    private fun handle(tile: ViewGroup, edge: DashboardResizeEdge) = tile.findViewWithTag<View>("dashboard_resize_${tile.tag}" + if (edge == DashboardResizeEdge.LEFT) "_left" else "")
    private fun drag(activity: MainActivity, id: String, edge: DashboardResizeEdge, expand: Boolean, cancel: Boolean = false): Rect {
        val grid = activity.findViewById<DashboardGridLayout>(R.id.dashboard_content)
        val card = tile(activity, id)
        val before = Rect(card.left, card.top, card.right, card.bottom)
        val neighbours = (0 until grid.childCount).map { grid.getChildAt(it) }.filter { it !== card }
            .associateWith { Rect(it.left, it.top, it.right, it.bottom) }
        val grip = handle(card, edge)
        assertEquals("Requested grip is visible for $id / $edge", View.VISIBLE, grip.visibility)
        val origin = IntArray(2).also(grid::getLocationOnScreen)
        val start = SystemClock.uptimeMillis()
        val x = if (edge == DashboardResizeEdge.LEFT) card.left - activity.dp(4).toFloat() else card.right + activity.dp(4).toFloat()
        val y = card.top + card.height / 2f
        fun event(action: Int, distance: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, origin[0] + x + distance, origin[1] + y, 0)
            event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat())
            grid.dispatchTouchEvent(event); event.recycle()
        }
        val distance = (grid.width - grid.paddingLeft - grid.paddingRight).toFloat() / grid.columnCount * .75f * edge.direction * if (expand) 1 else -1
        event(MotionEvent.ACTION_DOWN, 0f)
        event(MotionEvent.ACTION_MOVE, distance)
        assertTrue("Card follows the physical gesture before release", if (expand) card.width > before.width() else card.width < before.width())
        assertTrue("Neighbours move during the drag", neighbours.any { (view, old) -> view.left != old.left || view.top != old.top })
        event(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, distance)
        return before
    }
    private fun assertGrid(activity: MainActivity) {
        val grid = activity.findViewById<GridLayout>(R.id.dashboard_content)
        assertFalse("Grid layout is released after snapping", grid.isLayoutSuppressed)
        val cards = (0 until grid.childCount).map { grid.getChildAt(it) }
        cards.forEach { a ->
            assertTrue("Cards remain within the grid", a.left >= 0 && a.right <= grid.width)
            assertEquals(activity.dp(160), a.height)
            cards.filter { it !== a }.forEach { b -> assertFalse("Cards do not overlap after snapping", Rect.intersects(Rect(a.left, a.top, a.right, a.bottom), Rect(b.left, b.top, b.right, b.bottom))) }
        }
    }
    @Test fun outerCardsExpandInwardInTwoAndFourColumnsIncludingRtl() {
        for (columns in listOf(2, 4)) for (rtl in listOf(false, true)) withDashboard(columns, rtl) { scenario ->
            val id = if (columns == 2) "post_rate" else "daily_streak"
            val edge = if (rtl) DashboardResizeEdge.RIGHT else DashboardResizeEdge.LEFT
            var before: Rect? = null
            scenario.onActivity { activity ->
                val card = tile(activity, id)
                assertEquals(View.GONE, handle(card, if (rtl) DashboardResizeEdge.LEFT else DashboardResizeEdge.RIGHT).visibility)
                before = drag(activity, id, edge, expand = true)
            }
            settle()
            scenario.onActivity { activity ->
                val card = tile(activity, id)
                assertEquals(2, card.getTag(R.id.dashboard_card_span))
                assertEquals("Expansion stays on the original row", before!!.top, card.top)
                assertEquals("The outer edge stays anchored", if (rtl) before!!.left else before!!.right, if (rtl) card.left else card.right)
                assertGrid(activity)
            }
        }
    }
    @Test fun fourColumnInteriorCardsCanGrowEitherWayAndShrinkBack() = withDashboard(4) { scenario ->
        scenario.onActivity { activity ->
            val card = tile(activity, "post_rate")
            val left = handle(card, DashboardResizeEdge.LEFT); val right = handle(card, DashboardResizeEdge.RIGHT)
            assertEquals(View.VISIBLE, left.visibility); assertEquals(View.VISIBLE, right.visibility)
            assertNotEquals("Both directions have distinct accessible names", left.contentDescription, right.contentDescription)
            assertTrue("Both grips preserve a centre pickup target", right.left - left.right >= activity.dp(48) - 1)
            drag(activity, "post_rate", DashboardResizeEdge.LEFT, expand = true)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(listOf("post_rate", "avg_views") + fixture.drop(2), TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            drag(activity, "post_rate", DashboardResizeEdge.LEFT, expand = false)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            drag(activity, "post_rate", DashboardResizeEdge.RIGHT, expand = true)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(2, tile(activity, "post_rate").getTag(R.id.dashboard_card_span))
            assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
            assertGrid(activity)
        }
    }
    @Test fun mixedWideNeighboursReflowAndCancellationRestoresTheAccountLayout() {
        val cards = listOf("avg_views", "follower_ratio", "daily_streak", "post_rate", "total_views")
        withDashboard(4, cards = cards, wide = setOf("follower_ratio")) { scenario ->
            var before: Rect? = null
            scenario.onActivity { activity -> before = drag(activity, "daily_streak", DashboardResizeEdge.LEFT, expand = true, cancel = true) }
            settle()
            scenario.onActivity { activity ->
                assertEquals(cards, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
                assertEquals(1, tile(activity, "daily_streak").getTag(R.id.dashboard_card_span))
                drag(activity, "daily_streak", DashboardResizeEdge.LEFT, expand = true)
            }
            settle()
            scenario.onActivity { activity ->
                val card = tile(activity, "daily_streak")
                assertEquals(before!!.top, card.top); assertEquals(before!!.right, card.right)
                assertEquals(listOf("avg_views", "post_rate", "daily_streak", "follower_ratio", "total_views"), TwidgetStore.dashboardCards(activity, activity.selectedAccount))
                assertGrid(activity)
            }
        }
    }
    @Test fun removeButtonWinsOverTheResizeHitArea() = withDashboard(4) { scenario ->
        scenario.onActivity { activity ->
            val grid = activity.findViewById<DashboardGridLayout>(R.id.dashboard_content)
            val card = tile(activity, "avg_views")
            val button = (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<ImageButton>().first()
            val point = IntArray(2).also(button::getLocationOnScreen)
            val origin = IntArray(2).also(grid::getLocationOnScreen)
            val start = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, point[0] + button.width / 2f, point[1] + button.height / 2f, 0)
                event.offsetLocation(-origin[0].toFloat(), -origin[1].toFloat()); grid.dispatchTouchEvent(event); event.recycle()
            }
        }
        settle()
        scenario.onActivity { activity ->
            assertFalse("Remove remains directly tappable", "avg_views" in TwidgetStore.dashboardCards(activity, activity.selectedAccount))
        }
    }
}
