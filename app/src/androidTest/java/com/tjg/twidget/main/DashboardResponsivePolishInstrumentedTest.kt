package com.tjg.twidget.main

import android.content.res.Configuration
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.oneui.floatingactioncontainer.FloatingToolbarLayout
import com.tjg.twidget.R
import com.tjg.twidget.data.ProfileStats
import com.tjg.twidget.ui.DashboardCardDesign
import com.tjg.twidget.ui.DashboardRecyclerView
import com.tjg.twidget.ui.FollowerCountCardView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardResponsivePolishInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun measure(view: View, widthDp: Int, heightDp: Int) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        repeat(2) {
            DashboardCardDesign.apply(view, height, width)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
    }

    @Test fun narrowAndLandscapeBriefsKeepTwoCompleteDescriptionLines() {
        instrumentation.runOnMainSync {
            for ((windowWidth, windowHeight, width, height) in listOf(
                listOf(320, 568, 296, 184), listOf(800, 1280, 340, 160), listOf(891, 411, 867, 120))) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                    screenWidthDp = windowWidth; screenHeightDp = windowHeight
                    smallestScreenWidthDp = minOf(windowWidth, windowHeight)
                    fontScale = 1f
                }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val card = LayoutInflater.from(context).inflate(R.layout.brief_dashboard_card, null)
                val title = card.findViewById<TextView>(R.id.brief_dashboard_title).apply { text = "Your brief is getting ready" }
                val message = card.findViewById<TextView>(R.id.brief_dashboard_message).apply {
                    text = "See how your audience is changing and find your next step."
                }
                measure(card, width, height)
                assertTrue("Description has room for two lines at $windowWidth × $windowHeight", message.maxLines >= 2)
                assertTrue(message.layout.height <= message.height)
                assertTrue(title.layout.height <= title.height)
                assertEquals("Complete short description", 0, message.layout.getEllipsisCount(message.lineCount - 1))
                assertEquals(700, title.typeface.weight)
                val content = card.findViewById<View>(R.id.brief_dashboard_content_group)
                assertTrue("Copy stays within its panel", content.bottom <= (content.parent as View).height)
                if (windowWidth > windowHeight) {
                    val icon = card.findViewById<View>(R.id.brief_dashboard_icon)
                    val iconRect = Rect(0, 0, icon.width, icon.height)
                    val textRect = Rect(0, 0, content.width, content.height)
                    (card as ViewGroup).offsetDescendantRectToMyCoords(icon, iconRect)
                    card.offsetDescendantRectToMyCoords(content, textRect)
                    assertTrue("Compact copy clears the icon", textRect.left - iconRect.right >= (11 * context.resources.displayMetrics.density).toInt())
                }
            }
        }
    }

    @Test fun countFooterStaysTogetherForPositiveZeroAndNegativeChange() {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Twidget)
            for (width in listOf(136, 156, 190, 280, 400)) for (delta in listOf(-21L, 0L, 18L)) {
                val card = FollowerCountCardView(context).apply {
                    setPadding(12, 12, 12, 12)
                    bind(ProfileStats("Sample", "sample", 8074, 1386, 100, 100), delta)
                }
                measure(card, width, 160)
                val fitted = requireNotNull(card.fittedLayout())
                val footer = fitted.lines.last().map { it.text }
                assertEquals("Footer must not share a count line", "Followers", footer.first())
                assertEquals(if (delta == 0L) 1 else 2, footer.size)
                assertFalse(fitted.lines.dropLast(1).flatten().any { it.text == "Followers" })
                assertTrue(fitted.height <= card.height - card.paddingTop - card.paddingBottom)
                assertTrue(fitted.bounds.all { it.width() <= card.width - card.paddingLeft - card.paddingRight })
            }
        }
    }

    @Test fun narrowRatioValuesKeepAVisibleGap() {
        assumeTrue(com.tjg.twidget.data.TwidgetStore.isOnboarded(instrumentation.targetContext))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val ratio = InteractionCards.comparison(activity, R.string.follower_ratio,
                    R.string.followers, R.string.following, 8074, 1386, null, false)
                fun textViews(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
                    if (view is ViewGroup) (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) } else emptyList()
                for (width in listOf(136, 156, 190)) {
                    measure(ratio, width, 160)
                    val numbers = textViews(ratio).filter { it.text.toString().any(Char::isDigit) }
                    assertEquals(2, numbers.size)
                    assertTrue("Values remain distinct at ${width}dp", numbers[1].left - numbers[0].right >= activity.dp(8))
                    numbers.forEach { assertTrue(it.paint.measureText(it.text.toString()) <= it.width + 1) }
                }
            }
        }
    }

    @Test fun desktopToolbarFollowsCenteredGridBounds() {
        assumeTrue(com.tjg.twidget.data.TwidgetStore.isOnboarded(instrumentation.targetContext))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assumeTrue(activity.resources.configuration.screenWidthDp > 1300)
                val grid = activity.findViewById<DashboardRecyclerView>(R.id.dashboard_content)
                assertNull("Status-bar overflow must not have a hard rectangular cutoff",
                    activity.findViewById<View>(R.id.dashboard_root).clipBounds)
                fun find(view: View): FloatingToolbarLayout? = if (view is FloatingToolbarLayout) view else
                    (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
                val toolbar = requireNotNull(find(activity.window.decorView))
                val gridRect = Rect().also(grid::getGlobalVisibleRect)
                val toolbarRect = Rect().also(toolbar::getGlobalVisibleRect)
                val mainBar = activity.findViewById<androidx.appcompat.widget.Toolbar>(dev.oneuiproject.oneui.design.R.id.toolbarlayout_main_toolbar)
                val barRect = Rect().also(mainBar::getGlobalVisibleRect)
                java.io.File(activity.cacheDir, "responsive-toolbar-bounds.txt").writeText(
                    "grid=$gridRect floating=$toolbarRect actual=$barRect parent=${mainBar.parent.javaClass.name} width=${mainBar.width}")
                assertEquals(grid.width, toolbar.width)
                assertTrue(kotlin.math.abs(gridRect.centerX() - toolbarRect.centerX()) <= 2)
                assertEquals("The actual title and menu bar follows the grid", grid.width, mainBar.width)
                assertTrue(kotlin.math.abs(gridRect.centerX() - barRect.centerX()) <= 2)
            }
        }
    }
}
