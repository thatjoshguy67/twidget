package com.tjg.twidget.main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.analytics.*
import com.tjg.twidget.ui.DashboardCardDesign
import com.tjg.twidget.ui.TwidgetFonts
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class InteractionCardDesignInstrumentedTest {
    @Test fun briefSubheadingsUseReal700WithoutSyntheticBold() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val snapshot = com.tjg.twidget.analytics.ActivityClient.snapshot(activity, activity.selectedAccount)
                val card = StreakCardFactory.createBrief(activity, snapshot)
                val caption = card.findViewById<TextView>(com.tjg.twidget.R.id.brief_streak_state)
                caption.paint.isFakeBoldText = true
                TwidgetFonts.applyTo(card)
                org.junit.Assert.assertEquals(700, caption.typeface.weight)
                assertTrue("Brief streak caption has no synthetic thickening", !caption.paint.isFakeBoldText)
                val heading = com.tjg.twidget.brief.BriefReviewViews.text(activity, "Brief card heading", 16f, bold = true)
                heading.paint.isFakeBoldText = true
                TwidgetFonts.applyTo(heading)
                org.junit.Assert.assertEquals(700, heading.typeface.weight)
                assertTrue(!heading.paint.isFakeBoldText)
                val width = activity.dp(400); val height = activity.dp(180)
                card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                card.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                card.draw(Canvas(bitmap))
                File(activity.filesDir,"brief-subheading-700.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun finalGraphAndTweetEdgesMatch() = compareGraphAndTweetEdges(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
    @Test fun finalGraphAndTweetEdgesMatchInLightMode() = compareGraphAndTweetEdges(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)

    private fun compareGraphAndTweetEdges(mode: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = com.tjg.twidget.ui.AppAppearance.outlineEffects(context)
        val previousMode = com.tjg.twidget.ui.AppAppearance.mode(context)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { com.tjg.twidget.ui.AppAppearance.setMode(context, mode) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    com.tjg.twidget.ui.AppAppearance.setOutlineEffects(activity, true)
                    val width = activity.dp(400); val height = activity.dp(160)
                    val images = listOf(DashboardCardType.POST_RATE, DashboardCardType.BEST_POST).map { type ->
                        val card = activity.dashboardBinder.createRecyclerCard(type)
                        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        card.layout(0, 0, width, height)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            val canvas = Canvas(it); canvas.drawColor(android.graphics.Color.BLACK); card.draw(canvas)
                        }
                    }
                    val edge = (0 until activity.dp(3)).map { images.first().getPixel(width / 2, it) }
                    val otherEdge = (0 until activity.dp(3)).map { images.last().getPixel(width / 2, it) }
                    edge.zip(otherEdge).forEach { (first, second) ->
                        for (channel in listOf<(Int) -> Int>(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue)) {
                            assertTrue("Full card edges match within the three-value surface tint difference", kotlin.math.abs(channel(first)-channel(second)) <= 3)
                        }
                    }
                    val pair = Bitmap.createBitmap(width, height * 2 + activity.dp(12), Bitmap.Config.ARGB_8888)
                    Canvas(pair).apply { drawColor(android.graphics.Color.BLACK); drawBitmap(images[0], 0f, 0f, null); drawBitmap(images[1], 0f, (height + activity.dp(12)).toFloat(), null) }
                    File(activity.filesDir,"matching-dashboard-outlines-${if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) "light" else "dark"}.png").outputStream().use { pair.compress(Bitmap.CompressFormat.PNG,100,it) }
                    pair.recycle(); images.forEach { it.recycle() }
                }
            }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync {
            com.tjg.twidget.ui.AppAppearance.setOutlineEffects(context, previous)
            com.tjg.twidget.ui.AppAppearance.setMode(context, previousMode)
        } }
    }

    @Test fun dashboardOutlinesFollowGlobalToggleIncludingBrief() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = com.tjg.twidget.ui.AppAppearance.outlineEffects(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    com.tjg.twidget.ui.AppAppearance.setOutlineEffects(activity, true)
                    val cards = listOf(DashboardCardType.MILESTONE, DashboardCardType.FOLLOWERS,
                        DashboardCardType.FOLLOWER_COUNT, DashboardCardType.DAILY_STREAK, DashboardCardType.TOP_FOLLOWERS,
                        DashboardCardType.ENGAGEMENT_MIX, DashboardCardType.ORIGINAL_REPLIES, DashboardCardType.FOLLOWER_RATIO,
                        DashboardCardType.AVG_VIEWS, DashboardCardType.BEST_POST)
                        .map { activity.dashboardBinder.createRecyclerCard(it) }
                    val contents = cards
                    contents.forEach { assertTrue("All dashboard surfaces get a rim", it.getTag(com.tjg.twidget.R.id.sesl_surface_highlight) != null) }
                    val alphas = contents.map { content ->
                        content.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(400), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(activity.dp(160), View.MeasureSpec.EXACTLY))
                        content.layout(0, 0, activity.dp(400), activity.dp(160))
                        fun rim(drawable: android.graphics.drawable.Drawable): com.tjg.twidget.ui.SeslRimDrawable? =
                            when (drawable) {
                                is com.tjg.twidget.ui.SeslRimDrawable -> drawable
                                is android.graphics.drawable.LayerDrawable -> (0 until drawable.numberOfLayers).firstNotNullOfOrNull { rim(drawable.getDrawable(it)) }
                                else -> null
                            }
                        val outline = requireNotNull(rim(requireNotNull(content.foreground)))
                        org.junit.Assert.assertEquals("Every card uses full rim opacity", 255, outline.alpha)
                        val image = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                        outline.setBounds(0, 0, image.width, image.height)
                        outline.draw(Canvas(image))
                        val pixels = IntArray(image.height) { y -> android.graphics.Color.alpha(image.getPixel(image.width / 2, y)) }
                        image.recycle()
                        pixels.toList()
                    }
                    alphas.drop(1).forEach { org.junit.Assert.assertEquals("Same outline alpha profile across all dashboard cards", alphas.first(), it) }

                    com.tjg.twidget.ui.AppAppearance.setOutlineEffects(activity, false)
                    contents.forEach { assertTrue("Disabling immediately removes each rim", it.getTag(com.tjg.twidget.R.id.sesl_surface_highlight) == null) }
                    val brief = cards.first()
                    val width = activity.dp(400); val height = activity.dp(160)
                    brief.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    brief.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    brief.draw(Canvas(bitmap))
                    assertTrue("Brief cannot reattach an outline during drawing", contents.first().getTag(com.tjg.twidget.R.id.sesl_surface_highlight) == null)
                    File(activity.filesDir,"brief-outline-disabled.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                    com.tjg.twidget.ui.AppAppearance.setOutlineEffects(activity, true)
                    contents.forEach { assertTrue("Existing cards regain the rim", it.getTag(com.tjg.twidget.R.id.sesl_surface_highlight) != null) }
                }
            }
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { com.tjg.twidget.ui.AppAppearance.setOutlineEffects(context, previous) } }
    }

    @Test fun replyCardShowsIconAndPreviousYear() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val original = requireNotNull(activity.analytics)
                val reply = requireNotNull(original.recentReplies.firstOrNull()).copy(isReply = true,
                    timestamp = java.time.LocalDate.of(2025, 9, 26).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                try {
                    activity.analytics = original.copy(best = reply)
                    val card = activity.postAnalyticsBinder.createGridCard(DashboardCardType.BEST_POST, activity.selectedAccount)
                    fun texts(view: View): List<TextView> = (if (view is TextView) listOf(view) else emptyList()) +
                        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
                    val date = texts(card).first { it.text.contains("2025") }
                    assertTrue("Reply date has a reply icon", date.compoundDrawablesRelative[0] != null)
                    val width = activity.dp(400); val height = activity.dp(160)
                    TwidgetFonts.applyTo(card)
                    DashboardCardDesign.apply(card, height, width)
                    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    card.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    card.draw(Canvas(bitmap))
                    File(activity.filesDir, "reply-card-year.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                } finally { activity.analytics = original }
            }
        }
    }

    @Test fun cachedReplyStatsAndHeadingWeights() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val data = requireNotNull(activity.analytics)
                org.junit.Assume.assumeTrue("Requires replies in cache", data.recentReplies.isNotEmpty())
                for (type in listOf(DashboardCardType.AVG_VIEWS, DashboardCardType.AVG_ENGAGEMENTS, DashboardCardType.DAILY_STREAK)) {
                    val card = activity.dashboardBinder.createRecyclerCard(type)
                    val width = activity.dp(200); val height = activity.dp(160)
                    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    card.layout(0, 0, width, height)
                    val label = card.findViewById<TextView>(com.tjg.twidget.R.id.metric_label)
                    org.junit.Assert.assertEquals(700, label.typeface.weight)
                    assertTrue(!label.paint.isFakeBoldText)
                    if (type != DashboardCardType.DAILY_STREAK) assertTrue(card.findViewById<TextView>(com.tjg.twidget.R.id.followers_value).text.toString() != "--")
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    card.draw(Canvas(bitmap))
                    File(activity.filesDir, "stats-${type.id}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun briefUsesTheStandardCardHeight() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                org.junit.Assert.assertEquals(DashboardCardType.DAILY_STREAK.preferredHeightDp(activity),
                    DashboardCardType.MILESTONE.preferredHeightDp(activity))
                val wrapper = activity.dashboardBinder.createRecyclerCard(DashboardCardType.MILESTONE)
                val width = activity.dp(400); val height = activity.dp(DashboardCardType.MILESTONE.preferredHeightDp(activity))
                wrapper.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                wrapper.layout(0, 0, width, height)
                val title = wrapper.findViewById<TextView>(com.tjg.twidget.R.id.brief_dashboard_title)
                val body = wrapper.findViewById<TextView>(com.tjg.twidget.R.id.brief_dashboard_message)
                assertTrue("Brief title remains visible", title.height > 0)
                assertTrue("Brief body remains visible", body.height > 0)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                wrapper.draw(Canvas(bitmap))
                File(activity.filesDir, "brief-standard-height.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test fun recycledCardsCanRebindAcrossLayouts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val wrapper = activity.dashboardBinder.createRecyclerCard(DashboardCardType.FOLLOWERS).apply {
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, activity.dp(160))
                }
                repeat(3) {
                    for (type in listOf(DashboardCardType.FOLLOWERS, DashboardCardType.ENGAGEMENT_RATE,
                        DashboardCardType.POST_RATE, DashboardCardType.FOLLOWING, DashboardCardType.MILESTONE)) {
                        activity.dashboardBinder.bindRecyclerCard(wrapper, type, refreshContent = true)
                    }
                }
                activity.dashboardBinder.onBriefPresentationUpdated()
                activity.dashboardBinder.updateBriefGeneration(false)
            }
        }
    }

    @Test fun expandedPostRateUsesTheSharedGraphShell() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = com.tjg.twidget.data.TwidgetStore.isDashboardCardExpanded(context, "post_rate")
        try {
            com.tjg.twidget.data.TwidgetStore.setDashboardCardExpanded(context, "post_rate", true)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    for (type in listOf(DashboardCardType.POST_RATE, DashboardCardType.FOLLOWERS)) {
                    val card = activity.dashboardBinder.createRecyclerCard(type)
                    assertTrue("Expanded rate has the shared graph summary", card.findViewById<View>(com.tjg.twidget.R.id.dashboard_stat_header) != null)
                    assertTrue("Expanded rate uses the shared chart", card.findViewById<View>(com.tjg.twidget.R.id.dashboard_stat_context) != null)
                    val width = activity.dp(400); val height = activity.dp(160)
                    card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    card.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    card.draw(Canvas(bitmap))
                    File(activity.filesDir, "shared-${type.id}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    val label = card.findViewById<TextView>(com.tjg.twidget.R.id.metric_label)
                    org.junit.Assert.assertEquals("Expanded title is left aligned", android.view.Gravity.START,
                        (label.parent as android.widget.LinearLayout).gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                    if (type == DashboardCardType.FOLLOWERS) {
                        card.findViewById<com.tjg.twidget.ui.MetricChartView>(com.tjg.twidget.R.id.followers_chart)
                            .setUnfollowSeries(listOf(3L, 5L, 2L, 4L, 0L, null, 8L))
                        val fixture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        card.draw(Canvas(fixture))
                        File(activity.filesDir, "shared-unfollows.png").outputStream().use { fixture.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        fixture.recycle()
                    }
                    }
                }
            }
        } finally {
            com.tjg.twidget.data.TwidgetStore.setDashboardCardExpanded(context, "post_rate", previous)
        }
    }

    @Test fun followerFeedbackUsesWholeCardWidth() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val card = com.tjg.twidget.followers.TopFollowersCardBinder(activity, {}).create(activity.selectedAccount)
                org.junit.Assume.assumeTrue("Requires cached top followers", card is android.widget.LinearLayout && card.childCount > 1)
                val group = card as android.widget.LinearLayout
                val width = activity.dp(400)
                val height = activity.dp(176)
                DashboardCardDesign.apply(card,height,width)
                card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                card.layout(0,0,width,height)
                assertTrue("Card owns the rounded clipping", card.clipToOutline)
                org.junit.Assert.assertEquals("Header starts at the top of the card", 0, group.getChildAt(0).top)
                for (index in 0 until group.childCount) {
                    val row = group.getChildAt(index)
                    org.junit.Assert.assertEquals("Touch target begins at card edge",0,row.left)
                    org.junit.Assert.assertEquals("Touch target spans full width",width,row.width)
                    row.isPressed = true
                    row.drawableHotspotChanged(width/2f,row.height/2f)
                    row.jumpDrawablesToCurrentState()
                    val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                    card.draw(Canvas(bitmap))
                    File(activity.filesDir,"follower-pressed-$index.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                    row.isPressed = false
                }
            }
        }
    }

    @Test fun refreshConfiguredProviderWhenRequested() {
        val account = InstrumentationRegistry.getArguments().getString("refreshAccount").orEmpty()
        org.junit.Assume.assumeTrue("Manual provider check requires refreshAccount", account.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val analytics = AnalyticsClient.refresh(context, account)
        val stored = InteractionStore.all(context, account)
        println("Reply verification: analytics=${analytics.recentReplies.size}, stored=${stored.posts.count { it.isReply }}")
        assertTrue("Provider replies persist into interaction data", analytics.recentReplies.all { reply ->
            stored.posts.any { it.isReply && reply.url.contains(it.id) }
        })
    }
    @Test fun renderPopulatedAndMissingCardsAtCompactAndWideWidths() {
        val now = System.currentTimeMillis()
        val data = InteractionData(
            daily = listOf(InteractionDay(LocalDate.now(ZoneOffset.UTC), InteractionCounts(likes=49,reposts=2,replies=8,quotes=1))),
            posts = (0 until 16).map { InteractionPost("fixture-$it", now, now, it >= 4, InteractionCounts()) },
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                for ((name, input) in listOf("populated" to data, "missing" to InteractionData())) {
                    for (widthDp in listOf(262, 400, 538)) {
                    for (heightDp in listOf(160, 214)) {
                        val cards = listOf("mix" to InteractionCards.mix(activity,input,7), "split" to InteractionCards.split(activity,input,7),
                            "ratio" to InteractionCards.comparison(activity, com.tjg.twidget.R.string.follower_ratio,
                                com.tjg.twidget.R.string.followers, com.tjg.twidget.R.string.following, 8080, 673, null, widthDp > 300),
                            "single" to InteractionCards.comparison(activity, com.tjg.twidget.R.string.interaction_split_title,
                                com.tjg.twidget.R.string.interaction_original, com.tjg.twidget.R.string.interaction_replies, 4, 0, null, widthDp > 300))
                        for ((kind, card) in cards) {
                            if (kind == "mix" && widthDp == 262) continue
                            val width = (widthDp*density).toInt()
                            val height = (heightDp*density).toInt()
                            TwidgetFonts.applyTo(card)
                            DashboardCardDesign.apply(card,height,width)
                            card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                            card.layout(0,0,width,height)
                            fun check(view: View) {
                                if (view is TextView && view.getTag(com.tjg.twidget.R.id.app_font_role) == TwidgetFonts.Role.DASHBOARD_HEADING) {
                                    org.junit.Assert.assertEquals("Heading uses actual 700 weight", 700, view.typeface.weight)
                                    assertTrue("Heading has no synthetic bold", !view.paint.isFakeBoldText)
                                    org.junit.Assert.assertEquals("Card heading retains the shared native size at $widthDp/$heightDp",
                                        android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, activity.resources.displayMetrics),
                                        view.textSize, .5f)
                                }
                                if (view is TextView) assertTrue("$kind/$name/$widthDp text height", view.height >= view.lineHeight)
                                if (view is ViewGroup) for (i in 0 until view.childCount) {
                                    val child = view.getChildAt(i)
                                    assertTrue("$kind/$name/$widthDp/$heightDp ${child.javaClass.simpleName} bottom=${child.bottom} parent=${view.height} padding=${view.paddingBottom}", child.bottom <= view.height-view.paddingBottom+1)
                                    check(child)
                                }
                            }
                            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                            card.draw(Canvas(bitmap))
                            File(activity.filesDir,"interaction-$kind-$name-$widthDp-$heightDp.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                            bitmap.recycle()
                            check(card)
                        }
                    }
                    }
                }
            }
        }
    }
}
