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
    private fun withDashboard(columns: Int, rtl: Boolean = false, block: (ActivityScenario<MainActivity>) -> Unit) {
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
                    activity.editModeController.setEditMode(true)
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
    private fun handle(tile: ViewGroup, edge: DashboardResizeEdge): View = tile.findViewWithTag(
        "dashboard_resize_${tile.tag}" + if (edge == DashboardResizeEdge.LEFT) "_left" else "")
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
    private fun resizePointer(activity: MainActivity, id: String, edge: DashboardResizeEdge): Pointer {
        val tile = tile(activity, id)
        val grip = handle(tile, edge)
        assertEquals("Requested physical grip is visible", View.VISIBLE, grip.visibility)
        return Pointer(grid(activity), if (edge == DashboardResizeEdge.LEFT) tile.left - activity.dp(3).toFloat()
            else tile.right + activity.dp(3).toFloat(), tile.top + tile.height / 2f)
    }
    private fun assertSettledGrid(activity: MainActivity) {
        val view = grid(activity)
        val tiles = (0 until view.childCount).map { view.getChildAt(it) }.filter { it.tag in fixture }
        for (a in tiles) {
            assertEquals(1f, a.alpha, .001f)
            assertEquals(0f, a.translationX, 1f)
            assertEquals(0f, a.translationY, 1f)
            tiles.filter { it !== a }.forEach { b ->
                assertFalse("Native slots remain separate while a resize is held",
                    Rect.intersects(Rect(a.left, a.top, a.right, a.bottom), Rect(b.left, b.top, b.right, b.bottom)))
            }
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
                assertEquals("post_rate", activity.editModeController.draggedCardId)
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

    @Test fun resizePreviewsStayOnValidSlotsAndUseHysteresisBeforeCommit() {
        for (columns in listOf(2, 4)) for (rtl in listOf(false, true)) withDashboard(columns, rtl) { scenario ->
            val id = if (columns == 2) "post_rate" else "daily_streak"
            val edge = if (rtl) DashboardResizeEdge.RIGHT else DashboardResizeEdge.LEFT
            lateinit var pointer: Pointer
            lateinit var original: ViewGroup
            var unit = 0f
            var oldWidth = 0
            var oldOuterEdge = 0
            scenario.onActivity { activity ->
                original = tile(activity, id)
                oldWidth = original.width
                oldOuterEdge = if (rtl) original.left else original.right
                val view = grid(activity)
                unit = (view.width - view.paddingLeft - view.paddingRight).toFloat() / columns * original.scaleX * edge.direction
                pointer = resizePointer(activity, id, edge)
                pointer.send(MotionEvent.ACTION_DOWN)
                pointer.send(MotionEvent.ACTION_MOVE, unit * .30f)
                assertEquals("Content keeps the compact state below the size threshold", oldWidth, original.width)
                assertEquals(1, original.getTag(R.id.dashboard_card_span))
                pointer.send(MotionEvent.ACTION_MOVE, unit * .70f)
            }
            settle()
            scenario.onActivity { activity ->
                val resized = tile(activity, id)
                assertSame("Resizing preserves the same card wrapper", original, resized)
                assertTrue(resized.width > oldWidth)
                assertEquals(2, resized.getTag(R.id.dashboard_card_span))
                assertEquals("Inward expansion anchors the outer edge", oldOuterEdge, if (rtl) resized.left else resized.right)
                assertFalse("Preview does not write preferences", TwidgetStore.isDashboardCardExpanded(activity, id, activity.selectedAccount))
                assertSettledGrid(activity)
                pointer.send(MotionEvent.ACTION_MOVE, unit * .50f)
            }
            settle(80)
            scenario.onActivity { activity ->
                assertEquals("Small boundary movement does not flip the layout", 2, tile(activity, id).getTag(R.id.dashboard_card_span))
                pointer.send(MotionEvent.ACTION_UP, unit * .50f)
            }
            settle()
            scenario.onActivity { activity ->
                assertTrue(TwidgetStore.isDashboardCardExpanded(activity, id, activity.selectedAccount))
                assertSettledGrid(activity)
            }
        }
    }

    @Test fun bothInteriorGripsWorkAndCancelRestoresTheOriginalSizeAndOrder() = withDashboard(4) { scenario ->
        for (edge in DashboardResizeEdge.entries) {
            lateinit var pointer: Pointer
            var distance = 0f
            scenario.onActivity { activity ->
                val source = tile(activity, "post_rate")
                assertEquals(View.VISIBLE, handle(source, DashboardResizeEdge.LEFT).visibility)
                assertEquals(View.VISIBLE, handle(source, DashboardResizeEdge.RIGHT).visibility)
                pointer = resizePointer(activity, "post_rate", edge)
                val view = grid(activity)
                distance = (view.width - view.paddingLeft - view.paddingRight) / 4f * .7f * source.scaleX * edge.direction
                pointer.send(MotionEvent.ACTION_DOWN)
                pointer.send(MotionEvent.ACTION_MOVE, distance)
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(2, tile(activity, "post_rate").getTag(R.id.dashboard_card_span))
                assertSettledGrid(activity)
                pointer.send(MotionEvent.ACTION_CANCEL, distance)
            }
            settle()
            scenario.onActivity { activity ->
                assertEquals(1, tile(activity, "post_rate").getTag(R.id.dashboard_card_span))
                assertEquals(fixture, activity.dashboardBinder.cardIds())
                assertEquals(fixture, TwidgetStore.dashboardCards(activity, activity.selectedAccount))
                assertFalse(TwidgetStore.isDashboardCardExpanded(activity, "post_rate", activity.selectedAccount))
            }
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

    @Test fun aProfileChangeCancelsAnUncommittedResize() = withDashboard(4) { scenario ->
        lateinit var pointer: Pointer
        var originalAccount = ""
        val other = "native-dashboard-other-profile"
        scenario.onActivity { activity ->
            originalAccount = activity.selectedAccount
            TwidgetStore.saveDashboardCards(activity, listOf("avg_views", "daily_streak"), other)
            pointer = resizePointer(activity, "post_rate", DashboardResizeEdge.RIGHT)
            pointer.send(MotionEvent.ACTION_DOWN)
            pointer.send(MotionEvent.ACTION_MOVE, grid(activity).width / 4f * .7f)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(2, tile(activity, "post_rate").getTag(R.id.dashboard_card_span))
            activity.selectedAccount = other
            activity.dashboardBinder.bindContent(refreshRemote = false)
        }
        settle()
        scenario.onActivity { activity ->
            assertFalse(activity.dashboardBinder.isResizing)
            assertEquals(listOf("avg_views", "daily_streak"), activity.dashboardBinder.cardIds())
            assertEquals(fixture, TwidgetStore.dashboardCards(activity, originalAccount))
            assertFalse(TwidgetStore.isDashboardCardExpanded(activity, "post_rate", originalAccount))
        }
    }

    @Test fun shrinkingCommitsOnceAndUndoRestoresTheExpandedSize() = withDashboard(4) { scenario ->
        lateinit var pointer: Pointer
        var unit = 0f
        scenario.onActivity { activity ->
            unit = grid(activity).width / 4f * .7f
            pointer = resizePointer(activity, "post_rate", DashboardResizeEdge.RIGHT)
            pointer.send(MotionEvent.ACTION_DOWN)
            pointer.send(MotionEvent.ACTION_MOVE, unit)
        }
        settle()
        scenario.onActivity { pointer.send(MotionEvent.ACTION_UP, unit) }
        settle()
        scenario.onActivity { activity ->
            assertTrue(TwidgetStore.isDashboardCardExpanded(activity, "post_rate", activity.selectedAccount))
            pointer = resizePointer(activity, "post_rate", DashboardResizeEdge.RIGHT)
            pointer.send(MotionEvent.ACTION_DOWN)
            pointer.send(MotionEvent.ACTION_MOVE, -unit)
            // An asynchronous dashboard refresh must not interrupt the resize.
            activity.render(refreshRemote = false)
        }
        settle()
        scenario.onActivity { activity ->
            assertEquals(1, tile(activity, "post_rate").getTag(R.id.dashboard_card_span))
            assertTrue(TwidgetStore.isDashboardCardExpanded(activity, "post_rate", activity.selectedAccount))
            pointer.send(MotionEvent.ACTION_UP, -unit)
        }
        settle()
        scenario.onActivity { activity ->
            assertFalse(TwidgetStore.isDashboardCardExpanded(activity, "post_rate", activity.selectedAccount))
            assertSettledGrid(activity)
            activity.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
        }
        settle()
        scenario.onActivity { activity ->
            assertTrue(TwidgetStore.isDashboardCardExpanded(activity, "post_rate", activity.selectedAccount))
            assertEquals(2, tile(activity, "post_rate").getTag(R.id.dashboard_card_span))
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
}
