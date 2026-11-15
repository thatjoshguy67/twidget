package com.tjg.twidget.main

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class DashboardRefinementInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun preserve(block: () -> Unit) {
        val prefs = instrumentation.targetContext.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        fun owned(key: String) = key.startsWith("dashboard_") || key == "refresh_on_launch"
        val saved = prefs.all.filterKeys(::owned)
        try { prefs.edit().putBoolean("refresh_on_launch", false).commit(); block() }
        finally {
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
    private fun measure(view: View, widthDp: Int, heightDp: Int = 160) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (heightDp * density).roundToInt()
        repeat(2) {
            com.tjg.twidget.ui.DashboardCardDesign.apply(view, height, width)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun expandedCardsAddEvidenceAndKeepHeadlineTypography() = preserve {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val previous = activity.analytics
                try {
                    val posts = (1..6).map { n -> com.tjg.twidget.analytics.PostSummary(
                        "https://x.com/test/status/$n", "Fixture post $n", n * 100L, n * 2L, n.toLong(), 1, 0, n * 3L,
                        System.currentTimeMillis() - n * 86400000L, "", "Fixture", activity.selectedAccount, "") }
                    activity.analytics = com.tjg.twidget.analytics.AnalyticsClient.analyticsFromPosts(activity.selectedAccount, 8000, 7, false, posts, cachedAt = System.currentTimeMillis())
                    for (card in listOf(DashboardCardType.AVG_VIEWS, DashboardCardType.TOTAL_VIEWS, DashboardCardType.ENGAGEMENT_RATE,
                        DashboardCardType.POST_RATE, DashboardCardType.FOLLOWER_RATIO, DashboardCardType.DAILY_STREAK)) {
                        val tile = activity.dashboardBinder.createCardPreview(card, 2)
                        measure(tile, 180)
                        val value = tile.findViewById<TextView>(R.id.followers_value)
                        val size = value.textSize
                        measure(tile, 392)
                        val panel = tile.findViewById<ViewGroup>(R.id.dashboard_stat_context)
                        assertEquals("$card expands into context", View.VISIBLE, panel.visibility)
                        assertTrue("$card adds meaningful context", panel.childCount >= 2)
                        assertEquals("$card expansion preserves headline text size", size, value.textSize, 1f)
                        val header = tile.findViewById<View>(R.id.dashboard_stat_header)
                        assertEquals("$card header shares the outer top inset", tile.paddingTop, header.top)
                        assertTrue("$card heading fits in the header", tile.findViewById<TextView>(R.id.metric_label).top >= 0)
                        assertTrue("$card context fits its bounds", (0 until panel.childCount).all { panel.getChildAt(it).bottom <= panel.height + 1 })
                        if (card == DashboardCardType.AVG_VIEWS) {
                            val chart = (0 until panel.childCount).map { panel.getChildAt(it) }.first { it.javaClass.simpleName == "MiniBars" }
                            assertTrue("Expanded plot uses the available vertical space", chart.height >= activity.dp(48))
                            assertEquals("Median row reaches the shared bottom inset", panel.height, panel.getChildAt(panel.childCount - 1).bottom)
                        }
                        val bitmap = Bitmap.createBitmap(tile.width, tile.height, Bitmap.Config.ARGB_8888)
                        tile.draw(Canvas(bitmap))
                        java.io.File(activity.filesDir, "refined-${card.id}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    for (width in listOf(342, 392, 520)) {
                        val graph = activity.dashboardBinder.createCardPreview(DashboardCardType.FOLLOWERS, 2)
                        measure(graph, width)
                        val bitmap = Bitmap.createBitmap(graph.width, graph.height, Bitmap.Config.ARGB_8888)
                        graph.draw(Canvas(bitmap))
                        val chart = descendants(graph).filterIsInstance<com.tjg.twidget.ui.MetricChartView>().first()
                        assertEquals("Graph uses concentric card/plot corners", (28f - 12f) * graph.resources.displayMetrics.density, chart.plotCornerRadiusPx, 1f)
                        val plot = com.tjg.twidget.ui.MetricChartView::class.java.getDeclaredField("plotRect").apply { isAccessible = true }
                            .get(chart) as android.graphics.RectF
                        val origin = android.graphics.Rect(0, 0, chart.width, chart.height)
                        (graph as ViewGroup).offsetDescendantRectToMyCoords(chart, origin)
                        assertEquals("Graph has only the shared outer top inset at $width", graph.paddingTop.toFloat(), origin.top + plot.top, 1f)
                        assertEquals("Graph has only the shared outer end inset at $width", graph.width - graph.paddingRight.toFloat(), origin.left + plot.right, 1f)
                        java.io.File(activity.filesDir, "refined-graph-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    val count = activity.dashboardBinder.createCardPreview(DashboardCardType.FOLLOWER_COUNT, 2)
                    measure(count, 392)
                    assertNull("Stylistic worded count has no side panel", count.findViewById<View>(R.id.dashboard_stat_context))
                    val lettering = count.findViewById<TextView>(R.id.followers_value)
                    assertEquals("Worded count uses the full card width", count.width - count.paddingLeft - count.paddingRight, lettering.width)
                    val bitmap = Bitmap.createBitmap(count.width, count.height, Bitmap.Config.ARGB_8888)
                    count.draw(Canvas(bitmap))
                    java.io.File(activity.filesDir, "refined-worded-count.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                } finally { activity.analytics = previous }
            }
        }
    }
    @Test fun briefRetainsArtworkAndFitsLargeText() {
        instrumentation.runOnMainSync {
            for (scale in listOf(1f, 1.4f, 2f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = scale }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_Twidget)
                val tile = android.view.LayoutInflater.from(context).inflate(R.layout.brief_dashboard_card, null)
                tile.findViewById<TextView>(R.id.brief_dashboard_title).text = "Your growth and content review"
                val body = tile.findViewById<TextView>(R.id.brief_dashboard_message)
                body.text = "You gained 18 followers today and 88 followers this week."
                for (width in listOf(264, 392, 520)) {
                    measure(tile, width)
                    val icon = tile.findViewById<View>(R.id.brief_dashboard_icon)
                    assertEquals("Artwork stays visible at $scale / $width", View.VISIBLE, icon.visibility)
                    assertTrue("Brief body has complete visible lines at $scale / $width", body.layout.getLineBottom(body.lineCount - 1) <= body.height + 1)
                }
            }
        }
    }
    @Test fun recreationAndAccessibleReorderPreserveAccountLayout() = preserve {
        val context = instrumentation.targetContext
        TwidgetStore.saveDashboardCards(context, TwidgetStore.DEFAULT_DASHBOARD_CARDS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var account = ""
            scenario.onActivity { activity ->
                account = activity.selectedAccount
                activity.editModeController.setEditMode(true)
            }
            android.os.SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val grid = activity.findViewById<ViewGroup>(R.id.dashboard_content)
                val card = grid.findViewWithTag<View>("avg_views")
                val before = TwidgetStore.dashboardCards(activity, account)
                assertTrue(card.performAccessibilityAction(R.id.dashboard_move_later_action, null))
                assertEquals(before.indexOf("avg_views") + 1, TwidgetStore.dashboardCards(activity, account).indexOf("avg_views"))
                val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.dashboard_scroll)
                scroll.scrollTo(0, activity.dp(300))
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(account, activity.selectedAccount)
                assertTrue("Editing survives recreation", activity.editModeController.editMode)
                assertTrue("Reading position survives recreation", activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.dashboard_scroll).scrollY > 0)
            }
        }
    }
}
