package com.tjg.twidget.main

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.GridLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class DashboardCardVariantsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun withLayout(block: (Context) -> Unit) {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        fun layoutKey(key: String) = key.startsWith("dashboard_") || key == "refresh_on_launch"
        val saved = prefs.all.filterKeys(::layoutKey)
        try {
            prefs.edit().putBoolean("refresh_on_launch", false).commit()
            TwidgetStore.dashboardCards(context)
            block(context)
        } finally {
            prefs.edit().apply {
                prefs.all.keys.filter(::layoutKey).forEach(::remove)
                saved.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
    }

    @Test fun countVariantsResizePersistAndKeepSharedRowHeight() = withLayout { context ->
        TwidgetStore.saveDashboardCards(context, listOf("followers", "follower_count", "follower_ratio", "daily_streak"))
        TwidgetStore.setDashboardCardExpanded(context, "follower_count", false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val graph = card(activity, "followers")
                assertNotNull(graph.findViewById<View>(R.id.followers_chart))
                assertNotNull("The revised graph has the Health-style side count", graph.findViewById<View>(R.id.followers_value))
                assertEquals(1, card(activity, "follower_count").getTag(R.id.dashboard_card_span))
                activity.editModeController.setEditMode(true)
                activity.editModeController.resizeDashboardCard(DashboardCardType.FOLLOWER_COUNT)
            }
            SystemClock.sleep(300)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(2, card(activity, "follower_count").getTag(R.id.dashboard_card_span))
                assertTrue(TwidgetStore.isDashboardCardExpanded(activity, "follower_count"))
                val grid = activity.findViewById<com.tjg.twidget.ui.DashboardRecyclerView>(R.id.dashboard_content)
                grid.dashboardCardChildren().groupBy { it.top }.values.forEach { row -> row.forEach { assertEquals(row.first().height, it.height) } }
                val text = card(activity, "follower_count").findViewById<com.tjg.twidget.ui.FollowerCountCardView>(R.id.followers_value)
                assertEquals("Written count remains complete", text.contentDescription.toString(),
                    requireNotNull(text.fittedLayout()).lines.flatten().joinToString(" ") { it.text })
            }
            SystemClock.sleep(300)
            scenario.recreate()
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals("Size survives recreation", 2, card(activity, "follower_count").getTag(R.id.dashboard_card_span))
                activity.editModeController.setEditMode(true)
                activity.editModeController.resizeDashboardCard(DashboardCardType.FOLLOWER_COUNT)
                assertFalse(TwidgetStore.isDashboardCardExpanded(activity, "follower_count"))
                assertFalse("Charts retain their fixed size", DashboardCardType.FOLLOWERS.supportsSizeVariants)
            }
        }
    }

    @Test fun allGraphCardsShareTheUpdatedFollowerLayout() = withLayout { context ->
        TwidgetStore.saveDashboardCards(context, listOf("milestone", "followers", "following", "posts", "likes", "follower_count"))
        TwidgetStore.setDashboardCardExpanded(context, "follower_count", false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val bindings = listOf(
                    Triple("followers", R.id.followers_value, R.id.followers_chart),
                    Triple("following", R.id.following_value, R.id.following_chart),
                    Triple("posts", R.id.posts_value, R.id.posts_chart),
                    Triple("likes", R.id.likes_value, R.id.likes_chart),
                )
                val reference = card(activity, "followers")
                val referenceChart = reference.findViewById<View>(R.id.followers_chart)
                for ((id, valueId, chartId) in bindings) {
                    val tile = card(activity, id)
                    val chart = tile.findViewById<com.tjg.twidget.ui.MetricChartView>(chartId)
                    val value = tile.findViewById<TextView>(valueId)
                    val label = tile.findViewById<TextView>(R.id.metric_label)
                    assertEquals("All graph cards share their grid size", reference.width to reference.height, tile.width to tile.height)
                    assertEquals("All charts have identical native bounds", referenceChart.left to referenceChart.width, chart.left to chart.width)
                    assertTrue("All graphs use the updated plot insets", chart.compactCardInsets)
                    if (tile.width / activity.resources.displayMetrics.density < 340f) {
                        assertEquals(View.GONE, tile.findViewById<View>(R.id.dashboard_graph_summary).visibility)
                        val heading = tile.findViewById<TextView>(R.id.dashboard_graph_heading)
                        assertTrue("Small graph retains its metric heading", heading.visibility == View.VISIBLE && heading.text.isNotBlank())
                    } else {
                        assertTrue("The metric name remains beneath the total", value.bottom <= label.top)
                        assertTrue("The complete value fits", value.layout.getEllipsisCount(0) == 0)
                    }
                }
            }
            InstrumentationRegistry.getArguments().getString("dashboard_visual_pause_ms")?.toLongOrNull()
                ?.coerceIn(0, 30_000)?.takeIf { it > 0 }?.let(SystemClock::sleep)
        }
    }

    @Test fun tweetPreviewGivesTextPriorityAndRestoresMediaAtWiderWidths() = withLayout { _ ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val previous = activity.analytics
                try {
                    val account = activity.selectedAccount
                    val post = com.tjg.twidget.analytics.PostSummary("https://x.com/$account/status/1",
                        "A longer post preview should use the available space for readable text when the card gets narrower.",
                        100, 5, 1, 1, 0, 7, System.currentTimeMillis(), "", "Dashboard fixture", account, "",
                        media = List(2) { com.tjg.twidget.analytics.PostMedia("photo", "", "Fixture media", 128, 100) })
                    activity.analytics = com.tjg.twidget.analytics.AnalyticsClient.analyticsFromPosts(account, 100, 1, false,
                        listOf(post), cachedAt = System.currentTimeMillis()).copy(best = post)
                    val tile = activity.postAnalyticsBinder.createGridCard(DashboardCardType.BEST_POST, account)
                    com.tjg.twidget.ui.TwidgetFonts.applyTo(tile)
                    for (widthDp in listOf(454, 392, 284, 392)) {
                        val width = activity.dp(widthDp)
                        val height = activity.dp(160)
                        repeat(2) {
                            com.tjg.twidget.ui.DashboardCardDesign.apply(tile, height, width)
                            fun forceMeasure(view: View) {
                                view.forceLayout()
                                if (view is ViewGroup) for (i in 0 until view.childCount) forceMeasure(view.getChildAt(i))
                            }
                            forceMeasure(tile)
                            tile.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            tile.layout(0, 0, width, height)
                        }
                        assertEquals("Media yields to the paragraph and returns on wider cards",
                            if (widthDp < 340) View.GONE else View.VISIBLE, tile.findViewById<View>(R.id.dashboard_post_media).visibility)
                        val report = tile.findViewById<TextView>(R.id.dashboard_view_report)
                        assertTrue("The report action stays available", report.hasOnClickListeners())
                        assertTrue("Report action keeps its full label", report.paint.measureText(report.text.toString()) <=
                            report.width - report.compoundPaddingLeft - report.compoundPaddingRight + 1)
                        fun bounds(view: View) = android.graphics.Rect(0, 0, view.width, view.height).also {
                            (tile as android.view.ViewGroup).offsetDescendantRectToMyCoords(view, it)
                        }
                        val inset = (14f * activity.resources.displayMetrics.density).roundToInt()
                        assertEquals("Heading follows the platform icon and its gutter", inset + activity.dp(26), bounds(tile.findViewById(R.id.dashboard_post_heading)).left)
                        for (id in listOf(R.id.dashboard_post_body, R.id.dashboard_post_divider)) {
                            assertEquals("Tweet content shares one left inset", inset, bounds(tile.findViewById(id)).left)
                        }
                        assertEquals("Report pill shares the right inset", width - inset, bounds(report).right)
                        assertTrue("Heading and report pill share a vertical centre instead of mismatched baselines",
                            kotlin.math.abs(bounds(tile.findViewById(R.id.dashboard_post_heading)).centerY() - bounds(report).centerY()) <= 1)
                        val body = tile.findViewById<TextView>(R.id.dashboard_post_body)
                        assertEquals("The date-only metadata accompanies the paragraph", 2, (body.parent as ViewGroup).childCount)
                        assertEquals("The divider already supplies the section gap", 0, body.paddingTop)
                        assertTrue("The dated paragraph retains at least three complete lines", body.height >= body.lineHeight * 3)
                        assertEquals("Paragraph ends at the bottom inset", height - activity.dp(10), bounds(body).bottom)
                        val media = tile.findViewById<android.view.ViewGroup>(R.id.dashboard_post_media)
                        assertEquals("The two shadowed previews remain stacked without a badge", 2, media.childCount)
                        if (media.visibility == View.VISIBLE) {
                            assertEquals("Preview shares the right inset", width - inset, bounds(media).right)
                            val mediaGap = (16f * activity.resources.displayMetrics.density).roundToInt()
                            assertEquals("Text keeps its clear media gutter through remeasurement", mediaGap, bounds(media).left - bounds(body).right)
                            assertTrue("Thumbnail fits inside the content row", media.getChildAt(0).height <= media.height)
                        }
                        assertTrue("The post itself remains actionable", tile.hasOnClickListeners())
                        assertTrue("Media opens the annotated viewer",
                            tile.findViewById<View>(R.id.dashboard_post_media).hasOnClickListeners())
                    }
                    val opened = mutableListOf<android.content.Intent>()
                    val monitor = object : android.app.Instrumentation.ActivityMonitor() {
                        override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                            if (intent.action != android.content.Intent.ACTION_VIEW) return null
                            opened += android.content.Intent(intent)
                            return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
                        }
                    }
                    instrumentation.addMonitor(monitor)
                    try {
                        assertTrue(tile.performClick())
                        val intent = opened.single()
                        assertEquals("com.twitter.android", intent.`package`)
                        assertEquals(post.url, intent.dataString)
                        assertTrue("X opens post detail instead of injecting the post into Home",
                            intent.getBooleanExtra("com.x.deeplink.EXTRA_INTERNAL_DEEPLINK", false))
                        assertEquals("Tweet navigation does not detach the caller's task", 0,
                            intent.flags and (android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
                        assertEquals(android.net.Uri.parse("android-app://${activity.packageName}"),
                            androidx.core.content.IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_REFERRER, android.net.Uri::class.java))
                    } finally { instrumentation.removeMonitor(monitor) }
                } finally { activity.analytics = previous }
            }
        }
    }

    @Test fun tweetMediaShadowFadesIntoCardPaddingInBothThemes() = withLayout { context ->
        val originalMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        try {
            for (mode in listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)) {
                instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, mode) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        val previous = activity.analytics
                        try {
                            val account = activity.selectedAccount
                            val post = com.tjg.twidget.analytics.PostSummary("https://x.com/$account/status/1",
                                "Exclusive - Here's the Galaxy S27 series' wallpapers! We have rich blues, vibrant pinks, a classy white, and burgundy. Very fruity Download them from @sammygurus below 👇",
                                100, 5, 1, 1, 0, 7, 0, "", "Fixture", account, "",
                                media = listOf(com.tjg.twidget.analytics.PostMedia("photo", "", "Fixture", 128, 100)))
                            activity.analytics = com.tjg.twidget.analytics.AnalyticsClient.analyticsFromPosts(account,
                                100, 1, false, listOf(post), cachedAt = System.currentTimeMillis()).copy(best = post)
                            val tile = activity.postAnalyticsBinder.createGridCard(DashboardCardType.BEST_POST, account) as ViewGroup
                            com.tjg.twidget.ui.TwidgetFonts.applyTo(tile)
                            val width = activity.dp(454)
                            val height = activity.dp(160)
                            repeat(2) {
                                com.tjg.twidget.ui.DashboardCardDesign.apply(tile, height, width)
                                tile.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                                tile.layout(0, 0, width, height)
                            }
                            val media = tile.findViewById<ViewGroup>(R.id.dashboard_post_media)
                            val frame = media.getChildAt(0) as ViewGroup
                            val image = frame.getChildAt(0) as android.widget.ImageView
                            image.visibility = View.VISIBLE
                            image.setImageDrawable(android.graphics.drawable.GradientDrawable().apply {
                                setColor(0xff447799.toInt())
                                cornerRadius = activity.dp(16).toFloat()
                            })
                            tile.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            tile.layout(0, 0, width, height)
                            fun bounds(view: View) = android.graphics.Rect(0, 0, view.width, view.height).also {
                                tile.offsetDescendantRectToMyCoords(view, it)
                            }
                            val imageBounds = bounds(image)
                            val gap = width - imageBounds.right
                            assertEquals("Thumbnail has equal right and bottom insets", gap, height - imageBounds.bottom)
                            val body = tile.findViewById<TextView>(R.id.dashboard_post_body)
                            val gutter = imageBounds.left - bounds(body).right
                            assertEquals("Single-photo tweet keeps the full text-to-image gutter",
                                (16f * activity.resources.displayMetrics.density).roundToInt(), gutter)
                            val outline = android.graphics.Outline().also { image.outlineProvider.getOutline(image, it) }
                            val outerRadius = com.tjg.twidget.ui.DashboardCardDesign.cornerRadius(tile.background)!!
                            assertEquals("Image corners are concentric with the card", outerRadius - gap, outline.radius, 1f)
                            fun draw() = android.graphics.Bitmap.createBitmap(width, height,
                                android.graphics.Bitmap.Config.ARGB_8888).also { tile.draw(android.graphics.Canvas(it)) }
                            val fixed = draw()
                            val bodyBounds = bounds(body)
                            assertTrue("Text ink stays out of the visible image gutter", (bodyBounds.top until bodyBounds.bottom).all { y ->
                                (bodyBounds.right until imageBounds.left).none { x -> fixed.getPixel(x, y) == body.currentTextColor }
                            })
                            // Reproduce the old parent clipping against the same real card.
                            tile.clipChildren = true
                            tile.clipToPadding = true
                            val clipped = draw()
                            try {
                                val y = bounds(media).bottom + activity.dp(2)
                                val x = bounds(frame).centerX()
                                assertTrue("The soft shadow reaches into the bottom inset in theme $mode",
                                    android.graphics.Color.red(fixed.getPixel(x, y)) < android.graphics.Color.red(clipped.getPixel(x, y)))
                                java.io.File(context.filesDir, "tweet-shadow-$mode.png").outputStream().use {
                                    fixed.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                            } finally { fixed.recycle(); clipped.recycle() }
                        } finally { activity.analytics = previous }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, originalMode) }
        }
    }

    @Test fun catalogueGroupsBothCountSizesAndAddsTheChosenSize() = withLayout { context ->
        TwidgetStore.saveDashboardCards(context, listOf("followers", "daily_streak"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity -> activity.editModeController.showAddCardDialog() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                windows().first { it.contentDescription?.startsWith(context.getString(R.string.card_group_metrics) + ", ") == true }.performClick()
            }
            SystemClock.sleep(350)
            InstrumentationRegistry.getArguments().getString("catalogue_visual_pause_ms")?.toLongOrNull()
                ?.coerceIn(0, 30_000)?.takeIf { it > 0 }?.let(SystemClock::sleep)
            instrumentation.runOnMainSync {
                val panel = windows().first { it.transitionName == "catalogue_panel_follower_count" }
                val variants = descendants(panel).filter { it.transitionName?.startsWith("catalogue_variant_follower_count_") == true }
                assertEquals("One panel contains both selectable sizes", 2, variants.size)
                assertTrue(variants.all { it.isShown && it.isClickable })
                variants.first { it.transitionName.endsWith("_2") }.performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(1, TwidgetStore.dashboardCards(activity).count { it == "follower_count" })
                assertTrue(TwidgetStore.isDashboardCardExpanded(activity, "follower_count"))
                assertEquals(2, card(activity, "follower_count").getTag(R.id.dashboard_card_span))
                activity.editModeController.addDashboardCard(DashboardCardType.FOLLOWER_COUNT, 1)
                assertTrue("Duplicate add cannot change the selected size", TwidgetStore.isDashboardCardExpanded(activity, "follower_count"))
            }
        }
    }

    @Test fun profileLayoutsAndSizesStayIndependent() = withLayout { context ->
        val first = listOf("followers", "follower_count", "follower_ratio")
        val second = listOf("daily_streak", "avg_views")
        TwidgetStore.saveDashboardCards(context, first, "layout_profile_a")
        TwidgetStore.saveDashboardCards(context, second, "layout_profile_b")
        TwidgetStore.setDashboardCardExpanded(context, "follower_count", true, "layout_profile_a")
        assertEquals(first, TwidgetStore.dashboardCards(context, "LAYOUT_PROFILE_A"))
        assertEquals(second, TwidgetStore.dashboardCards(context, "layout_profile_b"))
        assertTrue(TwidgetStore.isDashboardCardExpanded(context, "follower_count", "layout_profile_a"))
        TwidgetStore.setDashboardCardExpanded(context, "follower_count", false, "layout_profile_b")
        assertFalse(TwidgetStore.isDashboardCardExpanded(context, "follower_count", "layout_profile_b"))
        assertTrue(TwidgetStore.isDashboardCardExpanded(context, "follower_count", "layout_profile_a"))
        TwidgetStore.resetDashboardCards(context, "layout_profile_b")
        assertEquals(first, TwidgetStore.dashboardCards(context, "layout_profile_a"))
        val accounts = TwidgetStore.accounts(context)
        if (accounts.size >= 2) {
            TwidgetStore.saveDashboardCards(context, first, accounts[0])
            TwidgetStore.saveDashboardCards(context, second, accounts[1])
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.selectedAccount = accounts[0]
                    activity.render(refreshRemote = false)
                    assertEquals(first, cardIds(activity))
                    activity.selectedAccount = accounts[1]
                    activity.render(refreshRemote = false)
                    assertEquals(second, cardIds(activity))
                    activity.selectedAccount = accounts[0]
                    activity.render(refreshRemote = false)
                    assertEquals(first, cardIds(activity))
                }
            }
        }
    }

    @Test fun loadedFollowerAvatarsKeepTheirFullCircularCropAfterRemeasure() = withLayout { context ->
        val account = TwidgetStore.accounts(context).first()
        org.junit.Assume.assumeTrue(com.tjg.twidget.followers.TopFollowersStore.read(context, account).top.isNotEmpty())
        TwidgetStore.saveDashboardCards(context, listOf("followers", "follower_count", "top_followers"), account)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 3000
            var loaded = false
            do {
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val avatars = descendants(card(activity, "top_followers")).filterIsInstance<android.widget.ImageView>()
                        .filter { (it.getTag(R.id.profile_image_request) as? String)?.startsWith("profile:") == true }
                    loaded = avatars.isNotEmpty() && avatars.all { it.drawable is android.graphics.drawable.BitmapDrawable }
                    if (loaded) {
                        com.tjg.twidget.ui.DashboardCardDesign.apply(card(activity, "top_followers"), card(activity, "top_followers").height)
                        descendants(card(activity, "top_followers")).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.forEach { text ->
                            assertTrue("Follower heading and row text fit their measured height: ${text.text}",
                                text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 1)
                        }
                        avatars.forEach { avatar ->
                            assertEquals("Loaded avatar fills the crop", 0, avatar.paddingLeft + avatar.paddingTop + avatar.paddingRight + avatar.paddingBottom)
                            assertNull("Placeholder surface is removed", avatar.background)
                            assertTrue(avatar.clipToOutline)
                            assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP, avatar.scaleType)
                            val outline = android.graphics.Outline()
                            avatar.outlineProvider.getOutline(avatar, outline)
                            assertTrue("Avatar retains its circular clipping outline", outline.canClip())
                        }
                    }
                }
                if (!loaded) SystemClock.sleep(50)
            } while (!loaded && SystemClock.uptimeMillis() < deadline)
            assertTrue("Cached follower photos finish loading", loaded)
        }
    }

    private fun cardIds(activity: MainActivity): List<String> = activity.dashboardBinder.cardIds()

    private fun card(activity: MainActivity, id: String): ViewGroup =
        activity.findViewById<com.tjg.twidget.ui.DashboardRecyclerView>(R.id.dashboard_content).findViewWithTag(id)

    private fun windows(): List<View> = WindowInspector.getGlobalWindowViews().flatMap(::descendants)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
