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
    private fun menu(): android.widget.ListView? = android.view.inspector.WindowInspector.getGlobalWindowViews()
        .flatMap(::descendants).filterIsInstance<android.widget.ListView>().firstOrNull { it.isShown && it.adapter != null }
    private fun choose(activity: MainActivity, action: Int) {
        val list = requireNotNull(menu()) { "A held card opens native actions" }
        val index = (0 until list.adapter.count).firstOrNull { list.adapter.getItem(it) == activity.getString(action) }
        assertNotNull("Requested card action is available", index)
        list.performItemClick(list.getChildAt(index!!) ?: View(activity), index, list.adapter.getItemId(index))
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
            assertFalse("The hold menu does not offer Move to", (0 until actions.adapter.count).any {
                actions.adapter.getItem(it) == activity.getString(R.string.dashboard_move_to)
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
