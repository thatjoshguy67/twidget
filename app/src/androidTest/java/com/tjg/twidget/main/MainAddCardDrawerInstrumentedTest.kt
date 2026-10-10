package com.tjg.twidget.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.MotionEvent
import android.view.InputDevice
import androidx.core.widget.NestedScrollView
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.setCardCornerRadius
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MainAddCardDrawerInstrumentedTest {
    @Test fun darkEditShadowRemainsVisibleAgainstBlack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val configuration = android.content.res.Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            val darkContext = context.createConfigurationContext(configuration)
            val density = darkContext.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val shadow = com.tjg.twidget.ui.CardShadowView(darkContext, dp(20).toFloat())
            shadow.layout(0, 0, dp(172), dp(152))
            val bitmap = Bitmap.createBitmap(shadow.width, shadow.height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.BLACK)
            shadow.draw(Canvas(bitmap))
            assertTrue("Dark shadow is visible just outside the card",
                Color.red(bitmap.getPixel(dp(32), dp(76))) > 0)
            assertEquals("Compact shadow stays clear of the distant clipping edge", Color.BLACK,
                bitmap.getPixel(dp(16), dp(76)))
            bitmap.recycle()
        }
    }

    @Test fun drawerShowsPreviewsAndAddsCardsWithoutDuplicates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var gestureDownTime = 0L
        fun cancelTouch() {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        cancelTouch()
        fun touch(action: Int, x: Float, y: Float) {
            if (action == MotionEvent.ACTION_DOWN) gestureDownTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(gestureDownTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        val context = instrumentation.targetContext
        fun observeTransition(activity: MainActivity, appears: Boolean): java.util.concurrent.atomic.AtomicBoolean {
            val observed = java.util.concurrent.atomic.AtomicBoolean(false)
            val grid = activity.findViewById<ViewGroup>(R.id.dashboard_content)
            var intermediateFrames = 0
            lateinit var listener: android.view.ViewTreeObserver.OnPreDrawListener
            listener = android.view.ViewTreeObserver.OnPreDrawListener {
                val cards = (0 until grid.childCount).mapNotNull { grid.getChildAt(it) as? ViewGroup }
                if (cards.isNotEmpty() && cards.all { card ->
                        card.scaleX > 0.9701f && card.scaleX < 0.9999f &&
                            (!appears || card.getChildAt(0).alpha in 0.001f..0.999f)
                    }) intermediateFrames++
                if (intermediateFrames >= 3) {
                    observed.set(true)
                    grid.viewTreeObserver.removeOnPreDrawListener(listener)
                }
                true
            }
            grid.viewTreeObserver.addOnPreDrawListener(listener)
            grid.postDelayed({ if (grid.viewTreeObserver.isAlive) grid.viewTreeObserver.removeOnPreDrawListener(listener) }, 1000)
            return observed
        }
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            prefs.edit().clear().putBoolean("onboarded", true).putString("username", "drawer_test")
                .putBoolean("refresh_on_launch", false).commit()
            TwidgetStore.saveStats(context, ProfileStats("Preview account", "drawer_test", 7813, 344, 490, 17000))
            val cards = TwidgetStore.DEFAULT_DASHBOARD_CARDS.filterNot {
                it in setOf(DashboardCardType.FOLLOWERS.id, DashboardCardType.FOLLOWING.id,
                    DashboardCardType.MILESTONE.id, DashboardCardType.DAILY_STREAK.id)
            }
            TwidgetStore.dashboardCards(context) // Complete first-run migrations before hiding fixture cards.
            TwidgetStore.saveDashboardCards(context, cards)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(600)
                scenario.onActivity { activity ->
                    val tweetBackground = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        activity, R.drawable.metric_card_clickable_bg,
                    )!!.mutate() as android.graphics.drawable.RippleDrawable
                    val radius = activity.dp(28).toFloat()
                    tweetBackground.setCardCornerRadius(radius)
                    for (layer in 0 until tweetBackground.numberOfLayers) {
                        assertEquals("Tweet surface and ripple mask match the edit border",
                            radius, (tweetBackground.getDrawable(layer) as android.graphics.drawable.GradientDrawable).cornerRadius, 0f)
                    }
                }
                instrumentation.runOnMainSync {
                    val button = windowViews().filterIsInstance<android.widget.Button>().first {
                        it.text.toString() == context.getString(R.string.top_followers_enable_shared_history)
                    }
                    assertTrue("Shared-history label fits with horizontal padding",
                        button.paint.measureText(button.text.toString()) + button.compoundPaddingLeft +
                            button.compoundPaddingRight <= button.width + 1)
                }
                scenario.onActivity { activity ->
                    activity.findViewById<dev.oneuiproject.oneui.layout.NavDrawerLayout>(R.id.main_toolbar_layout)
                        .setExpanded(false, animate = false)
                }
                SystemClock.sleep(150)
                scenario.onActivity { activity ->
                    val scroll = activity.findViewById<NestedScrollView>(R.id.dashboard_scroll)
                    val appBar = allViews(activity.findViewById(android.R.id.content))
                        .filterIsInstance<com.google.android.material.appbar.AppBarLayout>().first()
                    scroll.startNestedScroll(androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL)
                    val consumed = IntArray(2)
                    scroll.dispatchNestedPreScroll(0, appBar.height * 2, consumed, null)
                    scroll.scrollBy(0, activity.dp(300))
                    scroll.stopNestedScroll()
                }
                SystemClock.sleep(300)
                val positionBeforeOverflow = IntArray(2)
                var profileIcon: android.graphics.drawable.Drawable? = null
                var noticesIcon: android.graphics.drawable.Drawable? = null
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                        dev.oneuiproject.oneui.design.R.id.toolbarlayout_main_toolbar)
                    activity.onPrepareOptionsMenu(toolbar.menu)
                    val heading = allViews(toolbar).filterIsInstance<TextView>().first { it.text == toolbar.title }
                    assertEquals("Scrolled tablet heading is hidden before opening overflow", 0f, heading.alpha, 0.01f)
                    profileIcon = toolbar.menu.findItem(R.id.menu_open_profile).icon
                    noticesIcon = toolbar.menu.findItem(R.id.menu_notices).icon
                    activity.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0).getLocationInWindow(positionBeforeOverflow)
                    assertTrue("Overflow opens", toolbar.showOverflowMenu())
                }
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                        dev.oneuiproject.oneui.design.R.id.toolbarlayout_main_toolbar)
                    activity.onPrepareOptionsMenu(toolbar.menu)
                    val heading = allViews(toolbar).filterIsInstance<TextView>().first { it.text == toolbar.title }
                    assertEquals("Opening overflow keeps the tablet heading hidden", 0f, heading.alpha, 0.01f)
                    assertSame("Preparing overflow retains the profile icon", profileIcon, toolbar.menu.findItem(R.id.menu_open_profile).icon)
                    assertSame("Preparing overflow retains the notices icon", noticesIcon, toolbar.menu.findItem(R.id.menu_notices).icon)
                    val position = IntArray(2)
                    activity.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0).getLocationInWindow(position)
                    assertArrayEquals("Opening overflow does not shift dashboard content", positionBeforeOverflow, position)
                    toolbar.hideOverflowMenu()
                }
                SystemClock.sleep(150)
                scenario.onActivity { activity ->
                    val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(
                        dev.oneuiproject.oneui.design.R.id.toolbarlayout_main_toolbar)
                    val heading = allViews(toolbar).filterIsInstance<TextView>().first { it.text == toolbar.title }
                    assertEquals("Closing overflow keeps the tablet heading hidden", 0f, heading.alpha, 0.01f)
                    activity.findViewById<NestedScrollView>(R.id.dashboard_scroll).scrollTo(0, 0)
                    activity.findViewById<dev.oneuiproject.oneui.layout.NavDrawerLayout>(R.id.main_toolbar_layout)
                        .setExpanded(false, animate = false)
                }
                SystemClock.sleep(150)
                lateinit var entryObserved: java.util.concurrent.atomic.AtomicBoolean
                var normalVerticalMargins = 0 to 0
                scenario.onActivity {
                    assertTrue("Bottom edit button is available in normal mode",
                        it.findViewById<View>(R.id.dashboard_edit_button).isShown)
                    entryObserved = observeTransition(it, appears = true)
                    val params = it.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0).layoutParams as ViewGroup.MarginLayoutParams
                    normalVerticalMargins = params.topMargin to params.bottomMargin
                    it.findViewById<View>(R.id.dashboard_edit_button).performClick()
                    assertTrue("Bottom button enters edit mode", it.editModeController.editMode)
                    assertEquals(View.GONE, it.findViewById<View>(R.id.dashboard_edit_button).visibility)
                }
                SystemClock.sleep(400)
                assertTrue("Every card animates size and shadow across several intermediate frames", entryObserved.get())
                scenario.onActivity {
                    val params = it.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0).layoutParams as ViewGroup.MarginLayoutParams
                    assertEquals("Edit mode adds no extra vertical spacing", normalVerticalMargins, params.topMargin to params.bottomMargin)
                }
                scenario.onActivity { it.editModeController.setEditMode(false) }
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    assertFalse("Leaving edit mode preserves the compact header",
                        activity.findViewById<dev.oneuiproject.oneui.layout.NavDrawerLayout>(R.id.main_toolbar_layout).isExpanded)
                }
                var editContentClicks = 0
                val editTapBounds = Rect()
                scenario.onActivity { activity ->
                    activity.editModeController.setEditMode(true)
                    val wrapper = activity.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0) as ViewGroup
                    val content = wrapper.getChildAt(1)
                    content.setOnClickListener { editContentClicks++ }
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, content.importantForAccessibility)
                }
                SystemClock.sleep(300)
                scenario.onActivity { activity ->
                    val wrapper = activity.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0) as ViewGroup
                    assertTrue("Edited card has laid out before tapping", wrapper.getChildAt(1).getGlobalVisibleRect(editTapBounds))
                }
                touch(MotionEvent.ACTION_DOWN, editTapBounds.centerX().toFloat(), editTapBounds.centerY().toFloat())
                touch(MotionEvent.ACTION_UP, editTapBounds.centerX().toFloat(), editTapBounds.centerY().toFloat())
                scenario.onActivity { activity ->
                    assertEquals("Edit mode taps cannot activate card content", 0, editContentClicks)
                    activity.editModeController.showAddCardDialog()
                }
                SystemClock.sleep(500)
                var anchoredBottom = 0
                var compactHeight = 0
                var intermediateHeight = 0
                instrumentation.runOnMainSync {
                    val initialSheet = windowViews().first { it.id == com.google.android.material.R.id.design_bottom_sheet }
                    anchoredBottom = initialSheet.bottom
                    compactHeight = initialSheet.height
                    assertTrue(windowViews().any { it.contentDescription?.toString() == "Add cards" && it.isShown })
                    val metricsRow = windowViews().first { it.contentDescription?.toString()?.startsWith("Account and audience,") == true } as ViewGroup
                    assertEquals("2", (metricsRow.getChildAt(metricsRow.childCount - 1) as android.widget.TextView).text.toString())
                    val tweetsRow = windowViews().first { it.contentDescription?.toString()?.startsWith("Tweet analysis,") == true } as ViewGroup
                    assertFalse(tweetsRow.isEnabled)
                    assertEquals(0.5f, tweetsRow.alpha)
                    assertEquals("All added", (tweetsRow.getChildAt(tweetsRow.childCount - 1) as android.widget.TextView).text.toString())
                    captureDrawer("figma-drawer-collapsed.png", (244 * context.resources.displayMetrics.density).toInt())
                    windowViews().first { it.contentDescription?.toString()?.startsWith("Your Brief,") == true }.performClick()
                    assertTrue(windowViews().any { it.contentDescription?.toString() == "Add Your Brief" && it.isShown })
                }
                SystemClock.sleep(120)
                instrumentation.runOnMainSync {
                    intermediateHeight = windowViews().first { it.id == com.google.android.material.R.id.design_bottom_sheet }.height
                    assertTrue("Drawer height moves gradually", intermediateHeight > compactHeight)
                }
                SystemClock.sleep(400)
                instrumentation.runOnMainSync {
                    val sheet = windowViews().first { it.id == com.google.android.material.R.id.design_bottom_sheet }
                    assertTrue("Drawer does not snap immediately to its final height", sheet.height > intermediateHeight)
                    assertEquals("Expanded drawer stays anchored to the bottom", anchoredBottom, sheet.bottom)
                    val row = windowViews().first { it.contentDescription?.toString()?.startsWith("Account and audience,") == true }
                    assertTrue(row.performClick())
                }
                SystemClock.sleep(120)
                instrumentation.runOnMainSync {
                    val closing = windowViews().first { it.transitionName == "catalogue_section_BRIEF" }
                    val opening = windowViews().first { it.transitionName == "catalogue_section_METRICS" }
                    assertTrue("Old category folds shut while the new category opens", closing.height > 0 && opening.height > 0)
                    assertEquals("Category previews retain full opacity", 1f, opening.alpha)
                    captureDrawer("figma-drawer-accordion-midpoint.png")
                }
                SystemClock.sleep(400)
                instrumentation.runOnMainSync {
                    val sheet = windowViews().first { it.id == com.google.android.material.R.id.design_bottom_sheet }
                    assertEquals("Switching categories keeps the drawer anchored", anchoredBottom, sheet.bottom)
                    assertFalse("Only one category is open", windowViews().any { it.contentDescription?.toString() == "Add Your Brief" && it.isShown })
                    captureDrawer("figma-drawer-expanded.png")
                    val catalogueScroll = windowViews().filterIsInstance<androidx.core.widget.NestedScrollView>()
                        .first { it.transitionName == "catalogue_scroll" }
                    assertFalse("Drawer avoids SESL window-inset fade bands", catalogueScroll.seslIsFadingEdgeEnabled())
                    catalogueScroll.scrollTo(0, (240 * context.resources.displayMetrics.density).toInt())
                    captureDrawer("figma-drawer-scrolled-edges.png")
                    catalogueScroll.scrollTo(0, 0)
                    val preview = windowViews().first { it.contentDescription?.toString() == "Add Followers" }
                    assertTrue("Preview panel is visible", preview.isShown)
                    val thumbnail = ((preview as ViewGroup).getChildAt(1) as ViewGroup).getChildAt(0)
                    val bitmap = Bitmap.createBitmap(thumbnail.width, thumbnail.height, Bitmap.Config.ARGB_8888)
                    thumbnail.draw(Canvas(bitmap))
                    assertEquals("Card preview corners are transparent", 0, Color.alpha(bitmap.getPixel(0, 0)))
                    assertTrue("The card preview contains content", Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 0)
                    bitmap.recycle()
                    assertTrue(preview.performClick())
                    assertEquals(cards + DashboardCardType.FOLLOWERS.id, TwidgetStore.dashboardCards(context))
                    val updatedRow = windowViews().first { it.contentDescription?.toString()?.startsWith("Account and audience,") == true } as ViewGroup
                    assertEquals("1", (updatedRow.getChildAt(updatedRow.childCount - 1) as android.widget.TextView).text.toString())
                    assertFalse("Added card disappears from the catalogue", windowViews().any {
                        it.contentDescription?.toString() == "Add Followers"
                    })
                }
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    captureDrawer("figma-drawer-single-preview.png")
                    val sheet = windowViews().first { it.id == com.google.android.material.R.id.design_bottom_sheet }
                    assertEquals("Removing an added preview keeps the drawer anchored", anchoredBottom, sheet.bottom)
                    val catalogueScroll = windowViews().filterIsInstance<androidx.core.widget.NestedScrollView>()
                        .first { it.transitionName == "catalogue_scroll" }
                    assertTrue("Fade viewport stays inside the resized drawer", catalogueScroll.height <= sheet.height)
                }
                scenario.onActivity { activity ->
                    activity.editModeController.addDashboardCard(DashboardCardType.FOLLOWERS)
                    assertEquals(1, TwidgetStore.dashboardCards(context).count { it == DashboardCardType.FOLLOWERS.id })
                }
                val drawerPickup = Rect()
                instrumentation.runOnMainSync {
                    val panel = windowViews().first { it.contentDescription?.toString() == "Add Following" && it.isShown } as ViewGroup
                    (panel.getChildAt(1) as ViewGroup).getChildAt(0).getGlobalVisibleRect(drawerPickup)
                }
                val beforeDrawerDrag = TwidgetStore.dashboardCards(context)
                touch(MotionEvent.ACTION_DOWN, drawerPickup.centerX().toFloat(), drawerPickup.centerY().toFloat())
                SystemClock.sleep(120)
                instrumentation.runOnMainSync {
                    val panel = windowViews().first { it.contentDescription?.toString() == "Add Following" && it.isShown } as ViewGroup
                    assertTrue("Drawer hold animates the preview before pickup", panel.getChildAt(1).scaleX < 1f)
                }
                SystemClock.sleep(300)
                scenario.onActivity {
                    assertEquals("Holding a drawer preview picks up its card", DashboardCardType.FOLLOWING.id, it.editModeController.draggedCardId)
                    assertEquals("Pickup does not persist a card before drop", beforeDrawerDrag, TwidgetStore.dashboardCards(context))
                }
                assertFalse("Pickup dismisses the drawer", windowViews().any { it.contentDescription?.toString() == "Add cards" && it.isShown })
                val firstSlot = Rect()
                scenario.onActivity { it.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0).getGlobalVisibleRect(firstSlot) }
                val dropX = (firstSlot.left + 16 * context.resources.displayMetrics.density)
                val dropY = (firstSlot.top + 16 * context.resources.displayMetrics.density)
                touch(MotionEvent.ACTION_MOVE, dropX, dropY)
                SystemClock.sleep(220)
                touch(MotionEvent.ACTION_UP, dropX, dropY)
                SystemClock.sleep(100)
                scenario.onActivity {
                    val card = it.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0) as ViewGroup
                    assertTrue("Dropped card has a landing bounce", kotlin.math.abs(card.scaleX - 1f) > 0.0001f)
                    assertTrue("Edit mode cards have the soft shadow", card.getChildAt(0) is com.tjg.twidget.ui.CardShadowView)
                    val grid = it.findViewById<ViewGroup>(R.id.dashboard_content)
                    assertFalse("Dashboard column does not crop card shadows", (grid.parent as ViewGroup).clipChildren)
                    val decor = it.window.decorView
                    val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    decor.draw(Canvas(bitmap))
                    val gridPosition = IntArray(2)
                    grid.getLocationInWindow(gridPosition)
                    val shadowX = gridPosition[0] - it.dp(8)
                    val shadowY = gridPosition[1] + card.top + card.height / 2
                    assertTrue("Compact dashboard shadow is gone before the clipped edge",
                        kotlin.math.abs(Color.red(bitmap.getPixel(shadowX, shadowY)) -
                            Color.red(bitmap.getPixel(gridPosition[0] - it.dp(20), shadowY))) <= 1)
                    it.openFileOutput("dashboard-edit-shadows.png", Context.MODE_PRIVATE).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    bitmap.recycle()
                }
                SystemClock.sleep(250)
                assertEquals("Drawer card lands in the chosen slot", DashboardCardType.FOLLOWING.id, TwidgetStore.dashboardCards(context).first())
                assertEquals(1, TwidgetStore.dashboardCards(context).count { it == DashboardCardType.FOLLOWING.id })
                val viewport = Rect()
                val pickup = Rect()
                scenario.onActivity { activity ->
                    val dashboard = activity.findViewById<ViewGroup>(R.id.dashboard_content)
                    val scrollView = activity.findViewById<NestedScrollView>(R.id.dashboard_scroll)
                    scrollView.scrollTo(0, 0)
                    scrollView.getGlobalVisibleRect(viewport)
                    dashboard.getChildAt(0).getGlobalVisibleRect(pickup)
                }
                lateinit var exitObserved: java.util.concurrent.atomic.AtomicBoolean
                scenario.onActivity {
                    exitObserved = observeTransition(it, appears = false)
                    it.editModeController.setEditMode(false)
                }
                SystemClock.sleep(400)
                assertTrue("Exit smoothly restores card size", exitObserved.get())
                scenario.onActivity {
                    val card = it.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0) as ViewGroup
                    assertFalse("Normal mode cards have no shadow", card.getChildAt(0) is com.tjg.twidget.ui.CardShadowView)
                    card.getGlobalVisibleRect(pickup)
                }
                lateinit var heldEntryObserved: java.util.concurrent.atomic.AtomicBoolean
                scenario.onActivity { heldEntryObserved = observeTransition(it, appears = true) }
                touch(MotionEvent.ACTION_DOWN, pickup.centerX().toFloat(), pickup.centerY().toFloat())
                SystemClock.sleep(120)
                scenario.onActivity {
                    assertFalse("Press feedback precedes edit mode", it.editModeController.editMode)
                    assertTrue("Holding visibly compresses the tile", it.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0).scaleX < 1f)
                }
                SystemClock.sleep(300)
                scenario.onActivity { assertTrue("Holding enters edit mode within 420ms", it.editModeController.editMode) }
                touch(MotionEvent.ACTION_UP, pickup.centerX().toFloat(), pickup.centerY().toFloat())
                SystemClock.sleep(250)
                assertTrue("Unheld cards animate over several frames when a held card enters editing", heldEntryObserved.get())
                scenario.onActivity {
                    it.findViewById<ViewGroup>(R.id.dashboard_content).getChildAt(0).getGlobalVisibleRect(pickup)
                }
                touch(MotionEvent.ACTION_DOWN, pickup.centerX().toFloat(), pickup.centerY().toFloat())
                SystemClock.sleep(750)
                scenario.onActivity { assertNotNull("Long press starts dragging", it.editModeController.draggedCardId) }
                val x = viewport.centerX().toFloat()
                touch(MotionEvent.ACTION_MOVE, x, (viewport.bottom - 12).toFloat())
                SystemClock.sleep(600)
                var downScroll = 0
                scenario.onActivity {
                    downScroll = it.findViewById<NestedScrollView>(R.id.dashboard_scroll).scrollY
                    assertTrue("Holding still near bottom keeps scrolling", downScroll > 0)
                }
                touch(MotionEvent.ACTION_MOVE, x, (viewport.top + 12).toFloat())
                SystemClock.sleep(350)
                scenario.onActivity {
                    assertTrue("Holding near top scrolls back up", it.findViewById<NestedScrollView>(R.id.dashboard_scroll).scrollY < downScroll)
                }
                touch(MotionEvent.ACTION_UP, x, (viewport.top + 12).toFloat())
            }
        } finally {
            cancelTouch()
            prefs.edit().clear().apply {
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

    private fun captureDrawer(filename: String, cropHeight: Int? = null) {
        val root = windowViews().first { it.contentDescription?.toString() == "Add cards" }
        val bitmap = Bitmap.createBitmap(root.width, cropHeight?.coerceAtMost(root.height) ?: root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = 28 * root.resources.displayMetrics.density
        canvas.clipPath(android.graphics.Path().apply {
            addRoundRect(0f, 0f, root.width.toFloat(), root.height.toFloat(), radius, radius, android.graphics.Path.Direction.CW)
        })
        root.draw(canvas)
        java.io.File(root.context.filesDir, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun windowViews(): List<View> = WindowInspector.getGlobalWindowViews().flatMap { allViews(it) }
    private fun allViews(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
}
